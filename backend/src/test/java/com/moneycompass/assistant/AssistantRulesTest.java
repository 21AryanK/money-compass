package com.moneycompass.assistant;

import com.moneycompass.domain.ProfileType;
import com.moneycompass.domain.RiskBand;
import com.moneycompass.engine.*;
import com.moneycompass.plan.InvestmentPlanner;
import com.moneycompass.score.ScoreResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assistant's grounded answers: questions land on the right topic, a
 * what-if's figures are computed here (not left to the model), and trading
 * tips are declined before any model is called.
 */
class AssistantRulesTest {

    private static final QuestionBank BANK = ParityFixtures.bank();
    private static final AssessmentEngine ENGINE = new AssessmentEngine(BANK);
    private static final SnapshotCalculator CALC = new SnapshotCalculator(ENGINE);
    private static final InvestmentPlanner PLANNER = new InvestmentPlanner();
    private final AssistantRules rules = new AssistantRules();

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sessions() {
        return (List<Map<String, Object>>) ParityFixtures.sessions().get("sessions");
    }

    @SuppressWarnings("unchecked")
    static AssistantContext context(Map<String, Object> s) {
        ProfileType profile = ProfileType.valueOf((String) s.get("profile"));
        Map<String, Map<String, Object>> raw = new LinkedHashMap<>((Map<String, Map<String, Object>>) s.get("answers"));
        ScoreResult score = ENGINE.score(raw);
        SnapshotCalculator.RiskAssessment r = CALC.assessRisk(raw, profile);
        var flow = CALC.waterfall(r.snapshot());
        return new AssistantContext(profile, raw.size(), score.total(), score.categoryBreakdown(), r.snapshot(), flow,
                PLANNER.priorities(r.snapshot(), flow), r.tolerance(), r.capacity(), r.band(), r.band(), r.allocation(),
                r.factors(), CALC.suggestedMonthly(r.snapshot()), 10);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "What should I do first?|first-steps",
            "Why is my score 58?|score",
            "How big should my emergency fund be?|emergency",
            "How do I clear my debt?|debt",
            "What happens in a market crash?|crash",
            "Should I take more risk?|more-risk",
            "Why am I in the balanced band?|band",
            "How can I save tax?|tax",
            "Will my money last?|retirement",
            "What is term insurance?|glossary",
            "What is a SIP?|glossary",
            "Where should my monthly investment go?|where-to-invest",
            "How do I stay on track?|on-track",
            "How can I improve debt?|improve",
            "What if I invest ₹10,000 a month for 15 years?|what-if",
            "what if i put 2 lakh once for 10 years|what-if",
            "Which stock should I buy?|stock-tips",
            "hi|greeting",
            "blah blah|other"
    })
    void questionsLandOnTheRightTopic(String question, String topic) {
        assertThat(rules.answer(question, context(sessions().get(3))).topic()).isEqualTo(topic);
    }

    @Test
    void whatIfFiguresAreComputedFromTheUsersOwnBand() {
        AssistantContext c = context(sessions().get(3));
        AssistantRules.RuleAnswer a = rules.answer("What if I invest ₹10,000 a month for 15 years?", c);
        double blended = PlanMath.round1(PlanMath.blendedReturn(c.allocation()));
        assertThat(a.text()).contains("₹10,000 a month over 15 years")
                .contains("You'd put in ₹18 lakh")
                .contains(Money.amount(PlanMath.sipFutureValue(10000, blended, 15)));
        assertThat(a.declined()).isFalse();
    }

    @Test
    void lumpSumsAreRecognisedAndTheirFollowUpStaysALumpSum() {
        AssistantRules.RuleAnswer a = rules.answer("what if I put 2 lakh once for 10 years", context(sessions().get(0)));
        assertThat(a.text()).contains("₹2,00,000 invested once over 10 years");
        assertThat(a.chips()).contains("What if I invest ₹4,00,000 once for 10 years?");
    }

    @Test
    void tradingTipsAreDeclined() {
        AssistantRules.RuleAnswer a = rules.answer("Which crypto coin should I buy for quick gains?", context(sessions().get(1)));
        assertThat(a.declined()).isTrue();
        assertThat(a.text()).contains("can't point you to specific stocks");
    }

    @Test
    void amountsAndYearsAreParsedFromFreeText() {
        assertThat(AssistantRules.parseWhatIf("invest 5k for 12 years")).containsExactly(5000, 12);
        assertThat(AssistantRules.parseWhatIf("what if i save 1.5 lakh")).containsExactly(150000, 0);
        assertThat(AssistantRules.parseWhatIf("₹25,000 a month")).containsExactly(25000, 0);
        assertThat(AssistantRules.parseWhatIf("for 3 years")).containsExactly(0, 3);
    }

    @Test
    void everySuggestionOnEveryScreenHasAnAnswerForEverySession() {
        for (Map<String, Object> s : sessions()) {
            AssistantContext c = context(s);
            for (String screen : List.of("score", "risk", "invest")) {
                for (String chip : rules.starterChips(c, screen)) {
                    AssistantRules.RuleAnswer a = rules.answer(chip, c);
                    assertThat(a.topic()).as("%s on %s", chip, screen).isNotEqualTo("other");
                    assertThat(a.text()).isNotBlank();
                    for (String followUp : a.chips()) {
                        assertThat(rules.answer(followUp, c).topic()).as("follow-up %s", followUp).isNotEqualTo("other");
                    }
                }
            }
            assertThat(rules.greeting(c)).contains(c.total() + "/100");
        }
    }

    @Test
    void aChosenBandIsReflectedInTheAnswers() {
        AssistantContext base = context(sessions().get(2));
        RiskBand other = base.calculatedBand() == RiskBand.AGGRESSIVE ? RiskBand.CONSERVATIVE : RiskBand.AGGRESSIVE;
        AssistantContext chosen = new AssistantContext(base.profile(), base.answered(), base.total(), base.breakdown(),
                base.snapshot(), base.flow(), base.priorities(), base.tolerance(), base.capacity(), base.calculatedBand(),
                other, SnapshotCalculator.ALLOCATIONS.get(other), base.factors(), base.planMonthly(), base.planYears());
        assertThat(rules.answer("Why am I in this risk band?", chosen).text())
                .contains("you've chosen to plan around " + other.name().toLowerCase());
    }
}
