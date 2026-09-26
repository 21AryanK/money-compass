package com.moneycompass.narrative;

import com.moneycompass.ai.AiCallResult;
import com.moneycompass.ai.ResilientChatClient;
import com.moneycompass.domain.ProfileType;
import com.moneycompass.domain.RiskBand;
import com.moneycompass.engine.FinancialSnapshot;
import com.moneycompass.engine.SnapshotCalculator.CapacityFactor;
import com.moneycompass.engine.SnapshotCalculator.Waterfall;
import com.moneycompass.risk.dto.RiskNarrative;
import com.moneycompass.score.dto.Narrative;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static com.moneycompass.engine.Money.amount;
import static com.moneycompass.engine.Money.inr;
import static com.moneycompass.narrative.NarrativeTemplates.label;

/**
 * Writes one draft of the score or risk narrative.
 *
 * <p>The model writes the prose; the numbers never come from it. Each prompt
 * carries the facts {@link NarrativeTemplates} computed from the user's
 * answers — rupee figures included — and the model is told to use only
 * those. A first draft uses the configured temperature; a regenerate passes
 * the earlier drafts back with an instruction to take a different angle, at
 * a higher temperature, so it reads as a genuinely new answer.
 *
 * <p>If no provider answers (or the answer can't be parsed), the draft is
 * assembled from the same facts by {@link NarrativeTemplates} and labelled
 * {@code template}, so the results page still works — and says so — without
 * a model.
 */
@Service
public class NarrativeService {

    private static final Logger log = LoggerFactory.getLogger(NarrativeService.class);

    public static final String TEMPLATE_PROVIDER = "template";
    public static final String TEMPLATE_MODEL = "money-compass rules";
    private static final double REGENERATE_TEMPERATURE = 0.9;

    private static final String SYSTEM_PROMPT = """
            You are Money Compass, a financial literacy educator writing for an
            Indian retail audience, in warm, plain English addressed to the
            reader as "you".

            Rules you must follow:
            - Every number you are given was computed by the application, not
              by you. Treat them as ground truth. Never invent, change or
              estimate a figure: only use numbers that appear in the facts.
            - This is educational content, not financial advice. Never name a
              specific fund, stock, insurer, bank or app. Generic instruments
              (index fund, PPF, NPS, ELSS, FD, term plan, liquid fund) are fine.
            - No headings, markdown or emojis.
            - Respond with nothing but the requested JSON.
            """;

    private static final Map<String, String> SCORE_ANGLES = Map.of(
            "overview", "an overview: the score, the strongest and weakest areas, and the single biggest money lever",
            "lever", "lead with the single most important money action for this person, then put the score in context",
            "future", "look ahead in time: what their current monthly saving could grow into, and what protects that",
            "spread", "the gap between their strongest and weakest areas, and why closing it lifts the total fastest",
            "habits", "their habits (tracking, automation, how they'd react to a fall) rather than their knowledge",
            "profile", "what matters most at their life stage, and how their results measure up to it");

    private static final Map<String, String> RISK_ANGLES = Map.of(
            "mechanics", "how tolerance and capacity combine into the band, and what is holding capacity back",
            "rupees", "what the allocation means in rupees for their monthly amount, and the long-run return it assumes",
            "stress", "a bad year: what a 30% equity fall would do to this mix, and how they said they'd react",
            "neighbours", "how this band compares with the bands either side of it, and why they landed here",
            "horizon", "how their time horizon shapes the equity share");

    private final ResilientChatClient chatClient;
    private final NarrativeTemplates templates;

    public NarrativeService(ResilientChatClient chatClient, NarrativeTemplates templates) {
        this.chatClient = chatClient;
        this.templates = templates;
    }

    public record ScoreInputs(int total, Map<String, Integer> breakdown, ProfileType profile,
                              FinancialSnapshot snapshot, Waterfall flow) {}

    public record RiskInputs(int tolerance, int capacity, RiskBand band, Map<String, Integer> allocation,
                             List<CapacityFactor> factors, FinancialSnapshot snapshot, ProfileType profile,
                             double monthlyAmount) {}

    // =================================================================== score

    public String[] bandCopy(int total) {
        return NarrativeTemplates.bandCopy(total);
    }

    public NarrativeDraft scoreDraft(ScoreInputs in, List<NarrativeDraft> previous) {
        long started = System.nanoTime();
        Random rnd = new Random();
        NarrativeTemplates.ScorePools pools = templates.scorePools(in.total(), in.breakdown(), in.profile(),
                in.snapshot(), in.flow(), rnd);
        DraftMemory mem = previous.isEmpty() ? DraftMemory.first() : DraftMemory.after(previous);
        NarrativeTemplates.Chosen plan = templates.chooseScore(pools, mem);

        try {
            String user = scorePrompt(in, pools, plan, previous);
            AiCallResult<Narrative> result = chatClient.call(messages(user), Narrative.class,
                    previous.isEmpty() ? null : REGENERATE_TEMPERATURE);
            Narrative n = result.content();
            if (blank(n.summary()) || empty(n.strengths()) || empty(n.gaps()) || empty(n.nextSteps())) {
                throw new IllegalStateException("The model left part of the narrative empty");
            }
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("summary", n.summary().trim());
            content.put("strengths", clip(n.strengths(), 4));
            content.put("gaps", clip(n.gaps(), 4));
            content.put("nextSteps", clip(n.nextSteps(), 5));
            return new NarrativeDraft(content, result.provider(), result.model(), result.latencyMs(), result.tokens(),
                    Instant.now().toString(), plan.pointIds(), plan.angle());
        } catch (RuntimeException e) {
            log.warn("Score narrative falling back to templates: {}", e.toString());
            return new NarrativeDraft(plan.content(), TEMPLATE_PROVIDER, TEMPLATE_MODEL, elapsedMs(started), null,
                    Instant.now().toString(), plan.pointIds(), plan.angle());
        }
    }

    private String scorePrompt(ScoreInputs in, NarrativeTemplates.ScorePools pools, NarrativeTemplates.Chosen plan,
                               List<NarrativeDraft> previous) {
        String categories = in.breakdown().entrySet().stream()
                .map(e -> label(e.getKey()) + " " + (e.getValue() == null ? "not assessed" : e.getValue() + "%"))
                .collect(Collectors.joining(", "));
        StringBuilder sb = new StringBuilder();
        sb.append("Profile: ").append(profileName(in.profile())).append('\n');
        sb.append("Literacy score: ").append(in.total()).append("/100 (").append(pools.bandTitle()).append(")\n");
        sb.append("Category scores: ").append(categories).append('\n');
        sb.append("Money snapshot:\n").append(snapshotFacts(in.snapshot()));
        sb.append("\nFacts computed from this person's answers. Use them — reworded in your own voice — and no other numbers.\n");
        sb.append("Strengths to draw on:\n").append(bullets(pools.strengths()));
        sb.append("Gaps to draw on:\n").append(bullets(pools.gaps()));
        sb.append("Next steps to draw on:\n").append(bullets(pools.nextSteps()));
        sb.append("\nPoints that suit this draft best: ").append(String.join("; ", plan.pointIds())).append('\n');
        sb.append("""

                Write JSON with:
                - summary: 3 or 4 sentences. Framing: %s.
                - strengths: 3 short sentences (2 if fewer apply).
                - gaps: 3 short sentences.
                - nextSteps: 4 concrete actions, each with its rupee figure where the facts give one.
                """.formatted(SCORE_ANGLES.getOrDefault(plan.angle(), SCORE_ANGLES.get("overview"))));
        appendPrevious(sb, previous, List.of("summary", "strengths", "gaps", "nextSteps"),
                "You may repeat a point about high-interest debt, reworded, because it matters most.");
        return sb.toString();
    }

    // ==================================================================== risk

    public NarrativeDraft riskDraft(RiskInputs in, List<NarrativeDraft> previous) {
        long started = System.nanoTime();
        Random rnd = new Random();
        NarrativeTemplates.RiskPools pools = templates.riskPools(in.tolerance(), in.capacity(), in.band(), in.allocation(),
                in.factors(), in.snapshot(), in.profile(), in.monthlyAmount(), rnd);
        DraftMemory mem = previous.isEmpty() ? DraftMemory.first() : DraftMemory.after(previous);
        NarrativeTemplates.Chosen plan = templates.chooseRisk(pools, mem);

        try {
            String user = riskPrompt(in, pools, plan, previous);
            AiCallResult<RiskNarrative> result = chatClient.call(messages(user), RiskNarrative.class,
                    previous.isEmpty() ? null : REGENERATE_TEMPERATURE);
            RiskNarrative n = result.content();
            if (blank(n.rationale()) || empty(n.considerations()) || empty(n.avoid())) {
                throw new IllegalStateException("The model left part of the rationale empty");
            }
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("rationale", n.rationale().trim());
            content.put("considerations", clip(n.considerations(), 5));
            content.put("avoid", clip(n.avoid(), 4));
            return new NarrativeDraft(content, result.provider(), result.model(), result.latencyMs(), result.tokens(),
                    Instant.now().toString(), plan.pointIds(), plan.angle());
        } catch (RuntimeException e) {
            log.warn("Risk narrative falling back to templates: {}", e.toString());
            return new NarrativeDraft(plan.content(), TEMPLATE_PROVIDER, TEMPLATE_MODEL, elapsedMs(started), null,
                    Instant.now().toString(), plan.pointIds(), plan.angle());
        }
    }

    private String riskPrompt(RiskInputs in, NarrativeTemplates.RiskPools pools, NarrativeTemplates.Chosen plan,
                              List<NarrativeDraft> previous) {
        Map<String, Integer> al = in.allocation();
        StringBuilder sb = new StringBuilder();
        sb.append("Profile: ").append(profileName(in.profile())).append('\n');
        sb.append("Risk tolerance: ").append(in.tolerance()).append("/100 (how they'd react to losses)\n");
        sb.append("Risk capacity: ").append(in.capacity()).append("/100 (whether their finances could absorb a fall)\n");
        sb.append("How capacity was reached:\n");
        in.factors().forEach(f -> sb.append("- ").append(f.label()).append(": ").append(f.delta() > 0 ? "+" : "").append(f.delta()).append('\n'));
        sb.append("Band being planned around: ").append(in.band().name().toLowerCase())
                .append(" (the band is the lower of tolerance and capacity; the user may also have chosen it)\n");
        sb.append("Allocation: equity ").append(al.get("equity")).append("%, debt ").append(al.get("debt"))
                .append("%, gold ").append(al.get("gold")).append("%, cash ").append(al.get("cash")).append("%\n");
        if (in.monthlyAmount() > 0) sb.append("Monthly amount being planned: ").append(inr(in.monthlyAmount())).append('\n');
        sb.append("Money snapshot:\n").append(snapshotFacts(in.snapshot()));
        sb.append("\nFacts computed from this person's answers. Use them — reworded in your own voice — and no other numbers.\n");
        sb.append("Possible framings of the rationale, already worked out:\n");
        pools.angles().stream().filter(a -> a.build() != null)
                .forEach(a -> sb.append("- (").append(a.id()).append(") ").append(a.build().get()).append('\n'));
        sb.append("Considerations to draw on:\n").append(bullets(pools.considerations()));
        sb.append("Things to avoid, to draw on:\n").append(bullets(pools.avoid()));
        sb.append("""

                Write JSON with:
                - rationale: 3 or 4 sentences. Framing: %s.
                - considerations: 4 practical points for this band.
                - avoid: 3 things to avoid at this band.
                """.formatted(RISK_ANGLES.getOrDefault(plan.angle(), RISK_ANGLES.get("mechanics"))));
        appendPrevious(sb, previous, List.of("rationale", "considerations", "avoid"), "");
        return sb.toString();
    }

    // ================================================================= helpers

    private static List<Message> messages(String user) {
        return List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(user));
    }

    /** Earlier drafts, so a regenerate is told what not to repeat. */
    private static void appendPrevious(StringBuilder sb, List<NarrativeDraft> previous, List<String> keys, String allowance) {
        if (previous.isEmpty()) return;
        sb.append("\nThis is draft ").append(previous.size() + 1).append(". Earlier drafts already said the text below. ")
                .append("Write a genuinely different take: a different opening, a different framing, and mostly different points. ")
                .append(allowance).append('\n');
        int n = 1;
        for (NarrativeDraft d : previous.subList(Math.max(0, previous.size() - 3), previous.size())) {
            sb.append("--- Earlier draft ").append(n++).append(" ---\n");
            for (String k : keys) {
                Object v = d.content() == null ? null : d.content().get(k);
                if (v instanceof List<?> list) list.forEach(item -> sb.append("- ").append(item).append('\n'));
                else if (v != null) sb.append(v).append('\n');
            }
        }
    }

    /** The user's own numbers as plain lines, for the prompt. Unknowns are stated as unknown. */
    public static String snapshotFacts(FinancialSnapshot s) {
        StringBuilder sb = new StringBuilder();
        if (s.age() != null) sb.append("- Age ").append(s.age()).append(s.yearsToRetire() != null ? ", about " + s.yearsToRetire() + " years to retirement at 60" : "").append('\n');
        if (s.incomeKnown()) {
            sb.append("- Monthly income ").append(inr(s.income())).append(", saving about ").append(Math.round(s.savingsRate())).append('%')
                    .append(s.rateKnown() ? "" : " (assumed)").append(" = ").append(amount(s.saving())).append(" a month; estimated spending ")
                    .append(amount(s.spend())).append(" a month\n");
        } else {
            sb.append("- Income not shared").append(s.income() != null && s.income() == 0 ? " (no regular income)" : "").append('\n');
        }
        if (s.efMonths() != null) {
            sb.append("- Emergency fund about ").append(NarrativeTemplates.capitalize(com.moneycompass.engine.PlanMath.pct(s.efMonths())))
                    .append(" months against a ").append(s.efTargetMonths()).append("-month target")
                    .append(s.efTarget() != null ? " (" + amount(s.efTarget()) + ")" : "").append('\n');
        } else {
            sb.append("- Emergency fund size unknown; target ").append(s.efTargetMonths()).append(" months\n");
        }
        sb.append("- High-interest debt: ").append(s.highInterestDebt() == null ? "not sure" : s.highInterestDebt() ? "yes" : "no").append('\n');
        if (s.horizonYears() != null) sb.append("- Needs most of the money in about ").append(s.horizonYears()).append(" years\n");
        if (s.dropScale() != null) sb.append("- Reaction to a 20% fall, 1 (sell everything) to 5 (buy more): ").append(s.dropScale()).append('\n');
        if (!s.missedTopics().isEmpty()) sb.append("- Topics they missed: ")
                .append(s.missedTopics().stream().map(FinancialSnapshot.MissedTopic::topic).collect(Collectors.joining(", "))).append('\n');
        return sb.toString();
    }

    private static String bullets(List<NarrativeTemplates.Candidate> cands) {
        StringBuilder sb = new StringBuilder();
        Set<String> seen = new HashSet<>();
        for (NarrativeTemplates.Candidate c : cands) {
            if (seen.add(c.id())) sb.append("- (").append(c.id()).append(") ").append(c.first()).append('\n');
        }
        return sb.toString();
    }

    private static String profileName(ProfileType p) {
        return switch (p) {
            case STUDENT -> "student";
            case PROFESSIONAL -> "working professional";
            case RETIREE -> "retiree";
        };
    }

    private static List<String> clip(List<String> items, int max) {
        return items.stream().filter(i -> i != null && !i.isBlank()).map(String::trim).limit(max).toList();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean empty(List<?> l) {
        return l == null || l.isEmpty();
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
