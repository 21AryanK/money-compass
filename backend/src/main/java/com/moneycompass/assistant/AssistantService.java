package com.moneycompass.assistant;

import com.moneycompass.ai.AiCallResult;
import com.moneycompass.ai.ResilientChatClient;
import com.moneycompass.domain.RiskBand;
import com.moneycompass.domain.RiskProfile;
import com.moneycompass.engine.AssessmentEngine;
import com.moneycompass.engine.FinancialSnapshot;
import com.moneycompass.engine.SessionContext;
import com.moneycompass.engine.SnapshotCalculator;
import com.moneycompass.narrative.NarrativeService;
import com.moneycompass.plan.InvestmentPlanner;
import com.moneycompass.repo.LiteracyScoreRepository;
import com.moneycompass.repo.RiskProfileRepository;
import com.moneycompass.score.ScoreResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.moneycompass.engine.Money.inr;
import static com.moneycompass.narrative.NarrativeTemplates.label;

/**
 * "Ask Compass": a conversation about the user's own results.
 *
 * <p>Each question first goes through {@link AssistantRules}, which works out
 * the topic and a grounded answer from the user's real numbers — every
 * what-if is computed there, in Java. The model then gets the user's full
 * results as context, the conversation so far, and that grounded answer, and
 * writes the reply in its own words without changing any figure. If no model
 * answers, the grounded answer is returned as-is, labelled {@code rules}.
 *
 * <p>Requests the assistant shouldn't answer — stock, crypto or trading tips —
 * are declined by the rules before a model is ever called.
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
    public static final String RULES_PROVIDER = "rules";
    private static final String RULES_MODEL = "money-compass rules";
    private static final double CHAT_TEMPERATURE = 0.5;
    private static final int HISTORY_TURNS = 8;

    private static final String SYSTEM_PROMPT = """
            You are Ask Compass, the assistant inside Money Compass, a financial
            literacy app for an Indian retail audience. You are talking to one
            person about their own assessment results, given below.

            Rules you must follow:
            - Their numbers were computed by the application. Never invent,
              change or estimate a figure about them; use the figures given.
            - Each user message ends with a "Grounded answer" worked out by the
              application for that question. Base your reply on it: keep every
              number in it exactly, but write it naturally, answer what they
              actually asked, and you may add a sentence of relevant context.
            - Educational only, not financial advice. Never name a specific
              fund, stock, insurer, bank or app, and never give trading tips.
            - Be concise: a short paragraph, or a few bullets when listing.
              Plain text with "•" bullets; no markdown headings or bold.
            """;

    private final SessionContext sessionContext;
    private final AssessmentEngine engine;
    private final SnapshotCalculator calculator;
    private final InvestmentPlanner planner;
    private final LiteracyScoreRepository scoreRepository;
    private final RiskProfileRepository riskRepository;
    private final AssistantRules rules;
    private final ResilientChatClient chatClient;

    public AssistantService(SessionContext sessionContext, AssessmentEngine engine, SnapshotCalculator calculator,
                            InvestmentPlanner planner, LiteracyScoreRepository scoreRepository,
                            RiskProfileRepository riskRepository, AssistantRules rules, ResilientChatClient chatClient) {
        this.sessionContext = sessionContext;
        this.engine = engine;
        this.calculator = calculator;
        this.planner = planner;
        this.scoreRepository = scoreRepository;
        this.riskRepository = riskRepository;
        this.rules = rules;
        this.chatClient = chatClient;
    }

    @Transactional(readOnly = true)
    public ChatIntro intro(UUID sessionId, UUID userId, String screen) {
        AssistantContext c = context(sessionId, userId, null, null);
        String provider = chatClient.primaryProvider();
        return new ChatIntro(rules.greeting(c), rules.starterChips(c, screen), provider, chatClient.modelFor(provider));
    }

    @Transactional(readOnly = true)
    public ChatResponse chat(UUID sessionId, UUID userId, ChatRequest request) {
        long started = System.nanoTime();
        AssistantContext c = context(sessionId, userId, request.planMonthly(), request.planYears());
        AssistantRules.RuleAnswer grounded = rules.answer(request.message(), c);
        List<String> followUps = grounded.chips();

        if (grounded.declined()) {
            return new ChatResponse(grounded.text(), followUps, RULES_PROVIDER, RULES_MODEL, elapsedMs(started), null);
        }
        try {
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(SYSTEM_PROMPT + "\n" + describe(c)));
            List<ChatRequest.Turn> history = request.history() == null ? List.of() : request.history();
            for (ChatRequest.Turn t : history.subList(Math.max(0, history.size() - HISTORY_TURNS), history.size())) {
                messages.add("assistant".equals(t.role()) ? new AssistantMessage(t.content()) : new UserMessage(t.content()));
            }
            messages.add(new UserMessage(request.message() + "\n\nGrounded answer (topic: " + grounded.topic() + "):\n" + grounded.text()));
            AiCallResult<String> result = chatClient.call(messages, String.class, CHAT_TEMPERATURE);
            String reply = result.content() == null ? "" : result.content().trim();
            if (reply.isEmpty()) throw new IllegalStateException("The model returned an empty reply");
            return new ChatResponse(reply, followUps, result.provider(), result.model(), result.latencyMs(), result.tokens());
        } catch (RuntimeException e) {
            log.warn("Assistant falling back to the grounded rules answer: {}", e.toString());
            return new ChatResponse(grounded.text(), followUps, RULES_PROVIDER, RULES_MODEL, elapsedMs(started), null);
        }
    }

    private AssistantContext context(UUID sessionId, UUID userId, Double planMonthly, Integer planYears) {
        SessionContext.Loaded ctx = sessionContext.load(sessionId, userId);
        FinancialSnapshot snap = calculator.snapshot(ctx.answers(), ctx.profile());
        ScoreResult score = scoreRepository.findBySessionId(sessionId)
                .map(s -> new ScoreResult(s.getTotal(), s.getCategoryBreakdown()))
                .orElseGet(() -> engine.score(ctx.answers()));
        SnapshotCalculator.RiskAssessment assessed = calculator.assessRisk(ctx.answers(), ctx.profile());
        RiskProfile stored = riskRepository.findBySessionId(sessionId).orElse(null);
        boolean useStored = stored != null && !stored.getCapacityFactors().isEmpty();
        RiskBand calculated = useStored ? stored.getRiskBand() : assessed.band();
        RiskBand active = stored != null ? stored.getActiveBand() : assessed.band();
        var flow = calculator.waterfall(snap);
        return new AssistantContext(ctx.profile(), ctx.answers().size(), score.total(), score.categoryBreakdown(), snap, flow,
                planner.priorities(snap, flow),
                useStored ? stored.getToleranceScore() : assessed.tolerance(),
                useStored ? stored.getCapacityScore() : assessed.capacity(),
                calculated, active, SnapshotCalculator.ALLOCATIONS.get(active),
                useStored ? stored.getCapacityFactors() : assessed.factors(),
                planMonthly != null ? planMonthly : calculator.suggestedMonthly(snap),
                planYears != null ? planYears : snap.horizonYears() != null ? snap.horizonYears() : 10);
    }

    /** The person's results, for the system prompt. */
    private static String describe(AssistantContext c) {
        Map<String, Integer> al = c.allocation();
        StringBuilder sb = new StringBuilder("\nThe person's results:\n");
        sb.append("- Profile: ").append(c.profile().name().toLowerCase()).append('\n');
        sb.append("- Literacy score ").append(c.total()).append("/100. By category: ")
                .append(c.breakdown().entrySet().stream()
                        .map(e -> label(e.getKey()) + " " + (e.getValue() == null ? "not assessed" : e.getValue() + "%"))
                        .collect(Collectors.joining(", "))).append('\n');
        sb.append("- Risk tolerance ").append(c.tolerance()).append("/100, capacity ").append(c.capacity())
                .append("/100, calculated band ").append(c.calculatedBand().name().toLowerCase())
                .append(c.activeBand() != c.calculatedBand() ? ", planning around " + c.activeBand().name().toLowerCase() + " by choice" : "")
                .append('\n');
        sb.append("- Allocation: equity ").append(al.get("equity")).append("%, debt ").append(al.get("debt"))
                .append("%, gold ").append(al.get("gold")).append("%, cash ").append(al.get("cash")).append("%\n");
        sb.append("- Capacity factors: ").append(c.factors().stream()
                .map(f -> f.label() + " (" + (f.delta() > 0 ? "+" : "") + f.delta() + ")")
                .collect(Collectors.joining("; "))).append('\n');
        sb.append("- Plan on screen: ").append(inr(c.planMonthly())).append(" a month for ").append(c.planYears()).append(" years\n");
        sb.append(NarrativeService.snapshotFacts(c.snapshot()));
        return sb.toString();
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
