package com.moneycompass.engine;

import com.moneycompass.domain.ProfileType;
import com.moneycompass.domain.Question;
import com.moneycompass.domain.RiskBand;
import com.moneycompass.plan.InvestmentPlanner;
import com.moneycompass.score.ScoreResult;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The Java engine must reproduce the prototype exactly. Each fixture session
 * was produced by running {@code prototype/index.html}'s own JavaScript: this
 * replays the same answers through {@link AssessmentEngine},
 * {@link SnapshotCalculator} and {@link InvestmentPlanner} and compares every
 * result — which questions were served in which order, the score, the risk
 * factors, the money snapshot, the priorities, and the full investment plan
 * including its breakdown cards.
 *
 * <p>Regenerate the fixtures after changing the prototype's engine, and this
 * test says whether the backend still agrees with it.
 */
class PrototypeParityTest {

    private static final QuestionBank BANK = ParityFixtures.bank();
    private static final AssessmentEngine ENGINE = new AssessmentEngine(BANK);
    private static final SnapshotCalculator CALC = new SnapshotCalculator(ENGINE);
    private static final InvestmentPlanner PLANNER = new InvestmentPlanner();

    @TestFactory
    @SuppressWarnings("unchecked")
    Stream<DynamicTest> everyPrototypeSessionIsReproduced() {
        Map<String, Object> fixture = ParityFixtures.sessions();
        int max = ((Number) fixture.get("maxQuestions")).intValue();
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) fixture.get("sessions");
        return java.util.stream.IntStream.range(0, sessions.size()).mapToObj(i -> {
            Map<String, Object> s = sessions.get(i);
            return DynamicTest.dynamicTest("session " + i + " (" + s.get("profile") + ")", () -> check(s, max));
        });
    }

    @SuppressWarnings("unchecked")
    private void check(Map<String, Object> s, int max) {
        ProfileType profile = ProfileType.valueOf((String) s.get("profile"));
        Map<String, Map<String, Object>> answersInOrder = new LinkedHashMap<>();
        Map<String, Map<String, Object>> given = (Map<String, Map<String, Object>>) s.get("answers");
        List<String> served = (List<String>) s.get("served");
        List<Number> progress = (List<Number>) s.get("progress");

        // ---- the adaptive flow: same question, in the same order, every step
        for (int i = 0; i < served.size(); i++) {
            List<Question> upcoming = ENGINE.upcoming(answersInOrder, profile, max);
            assertThat(upcoming).as("step %d", i).isNotEmpty();
            assertThat(upcoming.getFirst().getCode()).as("question served at step %d", i).isEqualTo(served.get(i));
            answersInOrder.put(served.get(i), given.get(served.get(i)));
            assertThat(answersInOrder.size() + ENGINE.upcoming(answersInOrder, profile, max).size())
                    .as("estimated total after step %d", i).isEqualTo(progress.get(i).intValue());
        }
        assertThat(ENGINE.upcoming(answersInOrder, profile, max)).as("nothing left at the end").isEmpty();
        assertThat(ENGINE.skippedAdvanced(answersInOrder, profile).stream().map(Question::getCode).toList())
                .isEqualTo(s.get("skippedAdvanced"));
        assertThat(ENGINE.describeUnknowns(answersInOrder)).isEqualTo(s.get("unknownNote"));

        // ---- score
        Map<String, Object> score = (Map<String, Object>) s.get("score");
        ScoreResult result = ENGINE.score(answersInOrder);
        assertThat(result.total()).isEqualTo(((Number) score.get("total")).intValue());
        Map<String, Object> breakdown = (Map<String, Object>) score.get("breakdown");
        breakdown.forEach((cat, v) -> assertThat(result.categoryBreakdown().get(cat))
                .as("breakdown %s", cat).isEqualTo(v == null ? null : ((Number) v).intValue()));

        // ---- risk
        Map<String, Object> risk = (Map<String, Object>) s.get("risk");
        SnapshotCalculator.RiskAssessment r = CALC.assessRisk(answersInOrder, profile);
        assertThat(r.tolerance()).isEqualTo(((Number) risk.get("tolerance")).intValue());
        assertThat(r.capacity()).isEqualTo(((Number) risk.get("capacity")).intValue());
        assertThat(r.band().name()).isEqualTo(risk.get("band"));
        List<Map<String, Object>> factors = (List<Map<String, Object>>) risk.get("factors");
        assertThat(r.factors()).hasSameSizeAs(factors);
        for (int i = 0; i < factors.size(); i++) {
            assertThat(r.factors().get(i).label()).isEqualTo(factors.get(i).get("label"));
            assertThat(r.factors().get(i).delta()).isEqualTo(((Number) factors.get(i).get("delta")).intValue());
        }

        // ---- snapshot
        FinancialSnapshot snap = r.snapshot();
        Map<String, Object> es = (Map<String, Object>) s.get("snapshot");
        assertNum(snap.age(), es.get("age"), "age");
        assertNum(snap.income(), es.get("income"), "income");
        assertNum(snap.savingsRate(), es.get("savingsRate"), "savingsRate");
        assertThat(snap.rateKnown()).isEqualTo(es.get("rateKnown"));
        assertNum(snap.spend(), es.get("spend"), "spend");
        assertNum(snap.saving(), es.get("saving"), "saving");
        assertNum(snap.efMonths(), es.get("efMonths"), "efMonths");
        assertNum(snap.efTargetMonths(), es.get("efTargetMonths"), "efTargetMonths");
        assertNum(snap.efTarget(), es.get("efTarget"), "efTarget");
        assertNum(snap.efHave(), es.get("efHave"), "efHave");
        assertNum(snap.efGap(), es.get("efGap"), "efGap");
        assertThat(snap.highInterestDebt()).isEqualTo(es.get("highInterestDebt"));
        assertNum(snap.horizonYears(), es.get("horizonYears"), "horizonYears");
        assertNum(snap.dropScale(), es.get("dropScale"), "dropScale");
        assertThat(snap.missedTopics().stream().map(FinancialSnapshot.MissedTopic::code).toList()).isEqualTo(es.get("missedTopics"));

        // ---- waterfall, suggestion, priorities
        SnapshotCalculator.Waterfall flow = CALC.waterfall(snap);
        Map<String, Object> ew = (Map<String, Object>) s.get("waterfall");
        if (ew == null) {
            assertThat(flow).isNull();
        } else {
            assertNum(flow.budget(), ew.get("budget"), "budget");
            assertNum(flow.toDebt(), ew.get("toDebt"), "toDebt");
            assertNum(flow.toEf(), ew.get("toEf"), "toEf");
            assertNum(flow.toInvest(), ew.get("toInvest"), "toInvest");
            assertNum(flow.efMonthsToFill(), ew.get("efMonthsToFill"), "efMonthsToFill");
        }
        assertNum(CALC.suggestedMonthly(snap), s.get("suggestedMonthly"), "suggestedMonthly");
        Map<String, Object> ep = (Map<String, Object>) s.get("priorities");
        InvestmentPlanner.Priorities pr = PLANNER.priorities(snap, flow);
        assertThat(pr.intro()).isEqualTo(ep.get("intro"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) ep.get("items");
        assertThat(pr.items()).hasSameSizeAs(items);
        for (int i = 0; i < items.size(); i++) {
            assertThat(pr.items().get(i).title()).isEqualTo(items.get(i).get("t"));
            assertThat(pr.items().get(i).detail()).isEqualTo(items.get(i).get("d"));
            assertThat(pr.items().get(i).amount()).isEqualTo(items.get(i).get("amt"));
        }

        // ---- investment plan
        Map<String, Object> plan = (Map<String, Object>) s.get("plan");
        Map<String, Object> in = (Map<String, Object>) plan.get("input");
        RiskBand band = RiskBand.valueOf((String) in.get("band"));
        Map<String, Integer> al = SnapshotCalculator.ALLOCATIONS.get(band);
        double monthly = ((Number) in.get("monthly")).doubleValue();
        int years = ((Number) in.get("years")).intValue();
        Set<String> vehicles = new LinkedHashSet<>((List<String>) in.get("vehicles"));
        double stepUp = ((Number) in.get("stepUp")).doubleValue();
        InvestmentPlanner.Projection p = PLANNER.project(monthly, years, al, vehicles, band, snap, stepUp);
        for (String k : List.of("totalInvested", "totalProjected", "totalLow", "totalHigh", "totalAfterFeesAndTax", "taxEstimate", "blendedRate")) {
            double actual = switch (k) {
                case "totalInvested" -> p.totalInvested();
                case "totalProjected" -> p.totalProjected();
                case "totalLow" -> p.totalLow();
                case "totalHigh" -> p.totalHigh();
                case "totalAfterFeesAndTax" -> p.totalAfterFeesAndTax();
                case "taxEstimate" -> p.taxEstimate();
                default -> p.blendedRate();
            };
            assertNum(actual, plan.get(k), k);
        }
        assertThat(p.slab()).isEqualTo(((Number) plan.get("slab")).intValue());
        List<Map<String, Object>> eItems = (List<Map<String, Object>>) plan.get("items");
        assertThat(p.items().stream().map(InvestmentPlanner.Item::key).toList())
                .isEqualTo(eItems.stream().map(m -> m.get("key")).toList());
        for (int i = 0; i < eItems.size(); i++) {
            assertNum(p.items().get(i).monthly(), eItems.get(i).get("monthly"), "item monthly " + i);
            assertNum(p.items().get(i).rate(), eItems.get(i).get("rate"), "item rate " + i);
            assertNum(p.items().get(i).projected(), eItems.get(i).get("projected"), "item projected " + i);
            assertNum(p.items().get(i).netValue(), eItems.get(i).get("netValue"), "item net " + i);
        }

        Map<String, Object> mixes = (Map<String, Object>) plan.get("mixes");
        Map<String, InvestmentPlanner.MixView> actualMixes = new HashMap<>();
        actualMixes.put("fund", PLANNER.fundMix(monthly, al, vehicles, band, snap, years));
        actualMixes.put("direct", PLANNER.directMix(monthly, al, vehicles, band, snap, years));
        actualMixes.put("debt", PLANNER.debtMix(monthly, al, years, snap));
        actualMixes.put("gold", PLANNER.goldMix(monthly, al, years));
        actualMixes.put("cash", PLANNER.cashMix(monthly, al, snap));
        mixes.forEach((key, v) -> {
            InvestmentPlanner.MixView actual = actualMixes.get(key);
            if (v == null) {
                assertThat(actual).as("mix %s", key).isNull();
                return;
            }
            Map<String, Object> expected = (Map<String, Object>) v;
            assertThat(actual).as("mix %s", key).isNotNull();
            assertThat(actual.intro()).as("mix %s intro", key).isEqualTo(expected.get("intro"));
            assertThat(actual.notes()).as("mix %s notes", key).isEqualTo(expected.get("notes"));
            List<Map<String, Object>> parts = (List<Map<String, Object>>) expected.get("parts");
            assertThat(actual.parts().stream().map(InvestmentPlanner.Part::key).toList())
                    .as("mix %s parts", key).isEqualTo(parts.stream().map(m -> m.get("key")).toList());
            for (int i = 0; i < parts.size(); i++) {
                assertNum(actual.parts().get(i).pct(), parts.get(i).get("pct"), "mix " + key + " pct " + i);
                assertNum(actual.parts().get(i).monthly(), parts.get(i).get("monthly"), "mix " + key + " monthly " + i);
                // The prototype's sector parts carry no rate of their own (all
                // are projected at the direct-equity rate); Java attaches it.
                if (parts.get(i).get("rate") != null) {
                    assertNum(actual.parts().get(i).rate(), parts.get(i).get("rate"), "mix " + key + " rate " + i);
                }
            }
        });
    }

    private static void assertNum(Number actual, Object expected, String what) {
        if (expected == null) {
            assertThat(actual).as(what).isNull();
            return;
        }
        assertThat(actual).as(what).isNotNull();
        double e = ((Number) expected).doubleValue();
        assertThat(actual.doubleValue()).as(what).isCloseTo(e, within(Math.max(1e-6, Math.abs(e) * 1e-9)));
    }
}
