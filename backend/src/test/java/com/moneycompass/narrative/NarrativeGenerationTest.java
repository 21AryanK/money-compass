package com.moneycompass.narrative;

import com.moneycompass.ai.AiCallResult;
import com.moneycompass.ai.ResilientChatClient;
import com.moneycompass.common.AiUnavailableException;
import com.moneycompass.domain.ProfileType;
import com.moneycompass.domain.RiskBand;
import com.moneycompass.engine.*;
import com.moneycompass.risk.dto.RiskNarrative;
import com.moneycompass.score.ScoreResult;
import com.moneycompass.score.dto.Narrative;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The narrative layer: templates build from every kind of real session
 * without failing, regenerate brings in new points and framings, the model's
 * prompt carries the grounded facts (and earlier drafts on regenerate), and a
 * model failure degrades to a labelled template draft instead of an error.
 */
class NarrativeGenerationTest {

    private static final QuestionBank BANK = ParityFixtures.bank();
    private static final AssessmentEngine ENGINE = new AssessmentEngine(BANK);
    private static final SnapshotCalculator CALC = new SnapshotCalculator(ENGINE);
    private final NarrativeTemplates templates = new NarrativeTemplates();

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sessions() {
        return (List<Map<String, Object>>) ParityFixtures.sessions().get("sessions");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> answers(Map<String, Object> s) {
        return new LinkedHashMap<>((Map<String, Map<String, Object>>) s.get("answers"));
    }

    private NarrativeService.ScoreInputs scoreInputs(Map<String, Object> s) {
        ProfileType profile = ProfileType.valueOf((String) s.get("profile"));
        Map<String, Map<String, Object>> raw = answers(s);
        ScoreResult score = ENGINE.score(raw);
        FinancialSnapshot snap = CALC.snapshot(raw, profile);
        return new NarrativeService.ScoreInputs(score.total(), score.categoryBreakdown(), profile, snap, CALC.waterfall(snap));
    }

    private NarrativeService.RiskInputs riskInputs(Map<String, Object> s, RiskBand band) {
        ProfileType profile = ProfileType.valueOf((String) s.get("profile"));
        SnapshotCalculator.RiskAssessment r = CALC.assessRisk(answers(s), profile);
        return new NarrativeService.RiskInputs(r.tolerance(), r.capacity(), band, SnapshotCalculator.ALLOCATIONS.get(band),
                r.factors(), r.snapshot(), profile, CALC.suggestedMonthly(r.snapshot()));
    }

    @Test
    void templatesBuildForEverySessionAndEveryBandAcrossRepeatedDrafts() {
        for (Map<String, Object> s : sessions()) {
            NarrativeService.ScoreInputs in = scoreInputs(s);
            List<NarrativeDraft> drafts = new ArrayList<>();
            for (int d = 0; d < 4; d++) {
                var pools = templates.scorePools(in.total(), in.breakdown(), in.profile(), in.snapshot(), in.flow(), new Random(d));
                var chosen = templates.chooseScore(pools, drafts.isEmpty() ? DraftMemory.first() : DraftMemory.after(drafts));
                assertThat((String) chosen.content().get("summary")).isNotBlank();
                assertThat((List<?>) chosen.content().get("strengths")).isNotEmpty();
                assertThat((List<?>) chosen.content().get("gaps")).isNotEmpty();
                assertThat((List<?>) chosen.content().get("nextSteps")).isNotEmpty();
                drafts.add(draft(chosen));
            }
            for (RiskBand band : RiskBand.values()) {
                NarrativeService.RiskInputs r = riskInputs(s, band);
                var pools = templates.riskPools(r.tolerance(), r.capacity(), band, r.allocation(), r.factors(), r.snapshot(),
                        r.profile(), r.monthlyAmount(), new Random(1));
                var chosen = templates.chooseRisk(pools, DraftMemory.first());
                assertThat((String) chosen.content().get("rationale")).isNotBlank();
                assertThat((List<?>) chosen.content().get("considerations")).hasSizeBetween(1, 4);
                assertThat((List<?>) chosen.content().get("avoid")).hasSizeBetween(1, 3);
            }
        }
    }

    @Test
    void regenerateBringsInNewPointsAndAFreshFramingButKeepsHighInterestDebt() {
        Map<String, Object> withDebt = sessions().stream()
                .filter(s -> Boolean.TRUE.equals(scoreInputs(s).snapshot().highInterestDebt()))
                .findFirst().orElseThrow();
        NarrativeService.ScoreInputs in = scoreInputs(withDebt);
        var pools = templates.scorePools(in.total(), in.breakdown(), in.profile(), in.snapshot(), in.flow(), new Random(7));

        var first = templates.chooseScore(pools, DraftMemory.first());
        var second = templates.chooseScore(pools, DraftMemory.after(List.of(draft(first))));

        assertThat(second.angle()).isNotEqualTo(first.angle());
        assertThat(second.pointIds()).isNotEqualTo(first.pointIds());
        long repeated = second.pointIds().stream().filter(first.pointIds()::contains).count();
        assertThat(repeated).as("most points should be new").isLessThan(second.pointIds().size() / 2 + 2);
        assertThat(second.pointIds()).contains("debt");
    }

    @Test
    void theModelWritesTheDraftFromGroundedFactsAndRegenerateSendsEarlierDrafts() {
        ResilientChatClient client = mock(ResilientChatClient.class);
        when(client.call(anyList(), eq(Narrative.class), any())).thenReturn(new AiCallResult<>(
                new Narrative("A fresh summary.", List.of("s1"), List.of("g1"), List.of("n1")), "ollama", "llama3.1:8b", 1234, 321));
        NarrativeService service = new NarrativeService(client, templates);
        NarrativeService.ScoreInputs in = scoreInputs(sessions().getFirst());

        NarrativeDraft first = service.scoreDraft(in, List.of());
        NarrativeDraft second = service.scoreDraft(in, List.of(first));

        assertThat(first.provider()).isEqualTo("ollama");
        assertThat(first.content().get("summary")).isEqualTo("A fresh summary.");
        assertThat(first.tokens()).isEqualTo(321);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> prompts = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Double> temperatures = ArgumentCaptor.forClass(Double.class);
        verify(client, times(2)).call(prompts.capture(), eq(Narrative.class), temperatures.capture());
        String firstPrompt = prompts.getAllValues().get(0).getLast().getText();
        String secondPrompt = prompts.getAllValues().get(1).getLast().getText();
        assertThat(firstPrompt).contains("Literacy score: " + in.total() + "/100").contains("Facts computed from this person's answers");
        assertThat(firstPrompt).doesNotContain("Earlier drafts");
        assertThat(secondPrompt).contains("This is draft 2").contains("A fresh summary.");
        assertThat(temperatures.getAllValues().get(0)).isNull();
        assertThat(temperatures.getAllValues().get(1)).isEqualTo(0.9);
    }

    @Test
    void whenNoModelAnswersTheDraftIsWrittenFromTemplatesAndSaysSo() {
        ResilientChatClient client = mock(ResilientChatClient.class);
        when(client.call(anyList(), any(), any())).thenThrow(new AiUnavailableException("down"));
        NarrativeService service = new NarrativeService(client, templates);
        Map<String, Object> s = sessions().get(5);

        NarrativeDraft score = service.scoreDraft(scoreInputs(s), List.of());
        NarrativeDraft risk = service.riskDraft(riskInputs(s, RiskBand.BALANCED), List.of());

        assertThat(score.provider()).isEqualTo(NarrativeService.TEMPLATE_PROVIDER);
        assertThat((String) score.content().get("summary")).isNotBlank();
        assertThat(risk.provider()).isEqualTo(NarrativeService.TEMPLATE_PROVIDER);
        assertThat((String) risk.content().get("rationale")).isNotBlank();
    }

    @Test
    void anEmptyModelAnswerIsRejectedInFavourOfTheTemplate() {
        ResilientChatClient client = mock(ResilientChatClient.class);
        when(client.call(anyList(), eq(RiskNarrative.class), any())).thenReturn(
                new AiCallResult<>(new RiskNarrative("", List.of(), List.of()), "ollama", "m", 5, null));
        NarrativeDraft risk = new NarrativeService(client, templates).riskDraft(riskInputs(sessions().get(9), RiskBand.GROWTH), List.of());
        assertThat(risk.provider()).isEqualTo(NarrativeService.TEMPLATE_PROVIDER);
    }

    private static NarrativeDraft draft(NarrativeTemplates.Chosen c) {
        return new NarrativeDraft(c.content(), "template", "t", 0, null, "now", c.pointIds(), c.angle());
    }
}
