package com.moneycompass.narrative;

import com.moneycompass.ai.AiCallResult;
import com.moneycompass.ai.ResilientChatClient;
import com.moneycompass.config.MoneyCompassProperties;
import com.moneycompass.domain.ProfileType;
import com.moneycompass.domain.RiskBand;
import com.moneycompass.engine.*;
import com.moneycompass.score.ScoreResult;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Against a real local Ollama, opt-in: set {@code MC_LIVE_OLLAMA_MODEL} (for
 * example {@code llama3.1:8b}) and, if it isn't on localhost:11434,
 * {@code MC_LIVE_OLLAMA_URL}; {@code MC_LIVE_DISABLE_THINKING=true} for a
 * thinking model such as qwen3, as {@code OLLAMA_DISABLE_THINKING} does in the
 * app. Proves the structured output actually parses
 * from a real model — including one that emits {@code <think>} blocks — and
 * that a regenerate comes back as a model draft, not the template fallback.
 */
@EnabledIfEnvironmentVariable(named = "MC_LIVE_OLLAMA_MODEL", matches = ".+")
class LiveOllamaNarrativeTest {

    @Test
    @SuppressWarnings("unchecked")
    void aRealModelWritesAndRegeneratesGroundedDrafts() {
        String model = System.getenv("MC_LIVE_OLLAMA_MODEL");
        String url = System.getenv().getOrDefault("MC_LIVE_OLLAMA_URL", "http://localhost:11434");
        OllamaChatModel chatModel = OllamaChatModel.builder()
                .ollamaApi(OllamaApi.builder().baseUrl(url).build())
                .options(OllamaChatOptions.builder().model(model).temperature(0.2).build())
                .build();
        ChatClient.Builder chatClient = ChatClient.builder(chatModel);
        if ("true".equals(System.getenv("MC_LIVE_DISABLE_THINKING"))) {
            chatClient.defaultOptions(OllamaChatOptions.builder().disableThinking());
        }
        MoneyCompassProperties props = new MoneyCompassProperties(
                new MoneyCompassProperties.Ai("ollama", "none", 0.2, 800),
                new MoneyCompassProperties.Questionnaire(30),
                new MoneyCompassProperties.Security("live-test-secret-key-that-is-long-enough", 24),
                new MoneyCompassProperties.Cors(List.of("http://localhost:4200")));
        ResilientChatClient client = new ResilientChatClient(props, chatClient.build(),
                CircuitBreakerRegistry.ofDefaults(), RetryRegistry.ofDefaults(), model, "-", "-");
        NarrativeService service = new NarrativeService(client, new NarrativeTemplates());

        QuestionBank bank = ParityFixtures.bank();
        AssessmentEngine engine = new AssessmentEngine(bank);
        SnapshotCalculator calc = new SnapshotCalculator(engine);
        Map<String, Object> session = ((List<Map<String, Object>>) ParityFixtures.sessions().get("sessions")).get(44);
        ProfileType profile = ProfileType.valueOf((String) session.get("profile"));
        Map<String, Map<String, Object>> raw = new LinkedHashMap<>((Map<String, Map<String, Object>>) session.get("answers"));
        ScoreResult score = engine.score(raw);
        FinancialSnapshot snap = calc.snapshot(raw, profile);

        var in = new NarrativeService.ScoreInputs(score.total(), score.categoryBreakdown(), profile, snap, calc.waterfall(snap));
        NarrativeDraft first = service.scoreDraft(in, List.of());
        NarrativeDraft second = service.scoreDraft(in, List.of(first));
        System.out.println("DRAFT 1 (" + first.provider() + ", " + first.latencyMs() + " ms, " + first.tokens() + " tokens): " + first.content());
        System.out.println("DRAFT 2 (" + second.provider() + ", " + second.latencyMs() + " ms): " + second.content());
        assertThat(first.provider()).isEqualTo("ollama");
        assertThat(second.provider()).isEqualTo("ollama");
        assertThat(second.content().get("summary")).isNotEqualTo(first.content().get("summary"));

        SnapshotCalculator.RiskAssessment r = calc.assessRisk(raw, profile);
        NarrativeDraft risk = service.riskDraft(new NarrativeService.RiskInputs(r.tolerance(), r.capacity(), RiskBand.BALANCED,
                SnapshotCalculator.ALLOCATIONS.get(RiskBand.BALANCED), r.factors(), snap, profile, calc.suggestedMonthly(snap)), List.of());
        System.out.println("RISK (" + risk.provider() + "): " + risk.content());
        assertThat(risk.provider()).isEqualTo("ollama");

        AiCallResult<String> chat = client.call(List.of(new SystemMessage("You are a concise assistant. Keep every number exactly."),
                new UserMessage("How big should my emergency fund be?\n\nGrounded answer (topic: emergency):\nFor you, 6 months of spending is about ₹3 lakh.")),
                String.class, 0.5);
        System.out.println("CHAT: " + chat.content());
        assertThat(chat.content()).doesNotContain("<think>").contains("3 lakh");
    }
}
