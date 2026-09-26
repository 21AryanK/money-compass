package com.moneycompass.engine;

import com.moneycompass.domain.ProfileType;
import com.moneycompass.domain.Question;
import com.moneycompass.domain.QuestionCategory;
import com.moneycompass.domain.RiskBand;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Turns answers into a {@link FinancialSnapshot}, the monthly savings
 * waterfall, and the itemised risk capacity. Pure, like
 * {@link AssessmentEngine}.
 */
@Component
public class SnapshotCalculator {

    public static final double INFLATION_PCT = 6;
    public static final double DEFAULT_SAVINGS_RATE = 15;
    public static final double HIGH_INTEREST_PCT = 36;
    /** Age the retirement maths plans toward (most Indian employers retire at 58–60). */
    public static final int RETIREMENT_AGE = 60;

    /** Months of spending an emergency fund should cover. Retirees have no salary to rebuild it from. */
    private static final Map<ProfileType, Integer> EF_TARGET_MONTHS = new EnumMap<>(Map.of(
            ProfileType.STUDENT, 3, ProfileType.PROFESSIONAL, 6, ProfileType.RETIREE, 12));
    private static final Map<String, Integer> HORIZON_YEARS = Map.of("A", 1, "B", 3, "C", 5, "D", 10);

    public static final Map<RiskBand, Map<String, Integer>> ALLOCATIONS = new EnumMap<>(Map.of(
            RiskBand.CONSERVATIVE, allocation(15, 60, 15, 10),
            RiskBand.MODERATE, allocation(30, 50, 10, 10),
            RiskBand.BALANCED, allocation(45, 35, 10, 10),
            RiskBand.GROWTH, allocation(65, 20, 10, 5),
            RiskBand.AGGRESSIVE, allocation(80, 10, 5, 5)));

    private final AssessmentEngine engine;

    public SnapshotCalculator(AssessmentEngine engine) {
        this.engine = engine;
    }

    private static Map<String, Integer> allocation(int equity, int debt, int gold, int cash) {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        m.put("equity", equity);
        m.put("debt", debt);
        m.put("gold", gold);
        m.put("cash", cash);
        return m;
    }

    public FinancialSnapshot snapshot(Map<String, Map<String, Object>> raw, ProfileType profile) {
        Map<String, Map<String, Object>> a = engine.effectiveAnswers(raw);

        Double income = Answers.number(a.get("monthly_income"));
        Double ageNum = Answers.number(a.get("age"));
        Integer age = ageNum == null ? null : (int) Math.round(ageNum);
        Double rate = Answers.number(a.get("budgeting_savings_rate"));
        double savingsRate = rate == null ? DEFAULT_SAVINGS_RATE : Math.min(rate, 90);
        Double spend = income == null ? null : income * (1 - savingsRate / 100);

        Double efMonths = Answers.number(a.get("saving_emergency_fund_months"));
        int efTargetMonths = EF_TARGET_MONTHS.get(profile);
        Double efTarget = spend == null ? null : spend * efTargetMonths;
        // Not knowing your cushion is planned for as having none.
        Double efHave = spend == null ? null : spend * (efMonths == null ? 0 : efMonths);

        String debt = sel(a, "debt_high_interest_status");
        String horizon = sel(a, "risk_horizon");
        Map<String, Object> drop = a.get("risk_market_drop_reaction");

        List<FinancialSnapshot.MissedTopic> missed = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : raw.entrySet()) {
            Question q = engine.bank().byCode(e.getKey());
            if (q == null || q.getKnowledgeTopic() == null || !q.isKnowledgeQuestion()) continue;
            double[] pts = engine.pointsFor(q, e.getValue());
            if (pts[0] < pts[1]) {
                missed.add(new FinancialSnapshot.MissedTopic(q.getCode(), q.getKnowledgeTopic(), Answers.isUnknown(e.getValue())));
            }
        }

        return new FinancialSnapshot(
                profile,
                age,
                age != null && profile != ProfileType.RETIREE ? Math.max(0, RETIREMENT_AGE - age) : null,
                income, savingsRate, rate != null,
                spend, income == null ? null : income * savingsRate / 100,
                efMonths, efTargetMonths, efTarget, efHave,
                efTarget == null ? null : Math.max(0, efTarget - efHave),
                "A".equals(debt) ? Boolean.TRUE : "B".equals(debt) ? Boolean.FALSE : null,
                sel(a, "debt_payoff_plan"),
                horizon, horizon == null ? null : HORIZON_YEARS.get(horizon),
                drop == null || Answers.isUnknown(drop) ? null : Answers.scale(drop),
                sel(a, "budgeting_expense_tracking"),
                sel(a, "saving_automatic_transfer"),
                sel(a, "debt_emi_share"),
                sel(a, "dependents"),
                sel(a, "saving_term_insurance"),
                sel(a, "saving_health_insurance"),
                Answers.number(a.get("retirement_corpus_estimate")),
                Answers.number(a.get("retiree_runway_years")),
                a.containsKey("retiree_runway_years"),
                sel(a, "retiree_debt_in_retirement"),
                sel(a, "retiree_healthcare_fund"),
                sel(a, "retiree_nomination_will"),
                missed);
    }

    /** An option code, "?" for "I don't know", or null if never asked. */
    private static String sel(Map<String, Map<String, Object>> a, String code) {
        Map<String, Object> v = a.get(code);
        if (v == null) return null;
        return Answers.isUnknown(v) ? "?" : Answers.selected(v);
    }

    // ------------------------------------------------------------ waterfall

    /**
     * Where each month's saving should go, in priority order: high-interest
     * debt first (nothing in the plan out-earns ~36%), then the emergency-fund
     * gap, then investing. The budget is what the user saves now, nudged up to
     * at least 10% of income. Null when income isn't known.
     */
    public Waterfall waterfall(FinancialSnapshot snap) {
        if (snap.income() == null || snap.income() <= 0) return null;
        double budget = snap.income() * Math.max(snap.savingsRate(), 10) / 100;
        double debtShare = Boolean.TRUE.equals(snap.highInterestDebt()) ? 0.5 : 0;
        double efShare = snap.efGap() != null && snap.efGap() > 0 ? (debtShare > 0 ? 0.25 : 0.5) : 0;
        double toDebt = debtShare > 0 ? Math.max(500, Money.roundTo(budget * debtShare, 500)) : 0;
        double toEf = efShare > 0 ? Math.max(500, Money.roundTo(budget * efShare, 500)) : 0;
        double toInvest = Math.max(500, Money.roundTo(budget - toDebt - toEf, 500));
        int months = toEf > 0 ? (int) Math.ceil(snap.efGap() / toEf) : 0;
        return new Waterfall(budget, toDebt, toEf, toInvest, months);
    }

    public record Waterfall(double budget, double toDebt, double toEf, double toInvest, int efMonthsToFill) {}

    /** Most SIPs start at ₹500 a month; a smaller slice can't be invested on its own. */
    public static final double MIN_SIP = 500;

    /**
     * What the investment plan starts from: the invest share of the
     * waterfall, or a typical starting SIP for the profile when income isn't
     * known.
     */
    public double suggestedMonthly(FinancialSnapshot s) {
        Waterfall flow = waterfall(s);
        if (flow != null) return flow.toInvest();
        if (s.income() != null && s.income() == 0) return MIN_SIP; // no regular income: start at the smallest SIP
        return s.profile() == ProfileType.STUDENT ? 1000 : 5000;
    }

    // ------------------------------------------------------------- capacity

    public record CapacityFactor(String label, int delta) {}

    /**
     * Risk capacity as a list of signed factors that sum to the score, so the
     * Risk screen can show exactly how the number was reached. Anything still
     * "I don't know" is read cautiously.
     */
    public List<CapacityFactor> capacityFactors(FinancialSnapshot s) {
        ProfileType p = s.profile();
        List<CapacityFactor> f = new ArrayList<>();
        int base = p == ProfileType.STUDENT ? 45 : p == ProfileType.PROFESSIONAL ? 70 : 50;
        f.add(new CapacityFactor(p == ProfileType.STUDENT ? "Starting point for students"
                : p == ProfileType.PROFESSIONAL ? "Starting point for working professionals" : "Starting point for retirees", base));

        if (s.age() != null) {
            if (p != ProfileType.RETIREE) {
                if (s.age() < 30) f.add(new CapacityFactor("Under 30 — decades to recover from any fall", 5));
                else if (s.age() >= 55) f.add(new CapacityFactor("55 or older — retirement is close", -10));
                else if (s.age() >= 45) f.add(new CapacityFactor("Aged 45–54 — less time before retirement", -5));
            } else if (s.age() >= 75) {
                f.add(new CapacityFactor("75 or older — a shorter horizon for any recovery", -5));
            }
        }

        if (s.efMonths() == null) {
            f.add(new CapacityFactor("Emergency fund not known — counted as none", 0));
        } else {
            int efPts = (int) Math.round(Math.min(s.efMonths() / s.efTargetMonths(), 1) * 20);
            double efM = Math.round(s.efMonths() * 10) / 10.0;
            String months = efM == Math.rint(efM) ? Long.toString((long) efM) : Double.toString(efM);
            f.add(new CapacityFactor("Emergency fund: " + months + (efM == 1 ? " month" : " months") + " vs. a "
                    + s.efTargetMonths() + "-month target", efPts));
        }

        if (s.highInterestDebt() == null) {
            f.add(new CapacityFactor("High-interest debt not known — assumed present", -15));
        } else if (s.highInterestDebt()) {
            boolean onPlan = "C".equals(s.payoffPlan()) || "D".equals(s.payoffPlan());
            f.add(new CapacityFactor(onPlan ? "High-interest debt, being paid down on a plan"
                    : "High-interest debt, no payoff plan yet", onPlan ? -10 : -15));
        }

        if (p != ProfileType.RETIREE) {
            if (s.horizon() != null) {
                switch (s.horizon()) {
                    case "A" -> f.add(new CapacityFactor("Money needed within a year", -15));
                    case "B" -> f.add(new CapacityFactor("1–3 year horizon", -8));
                    case "D" -> f.add(new CapacityFactor("7+ year horizon", 8));
                    case "?" -> f.add(new CapacityFactor("Horizon not known — assumed short", -8));
                    default -> { }
                }
            }
            if (s.rateKnown() && s.savingsRate() >= 20)
                f.add(new CapacityFactor("Saving " + Math.round(s.savingsRate()) + "% of income", 5));
            if (s.rateKnown() && s.savingsRate() < 5) f.add(new CapacityFactor("Saving under 5% of income", -5));
        }

        if ("C".equals(s.emiBand())) f.add(new CapacityFactor("20–40% of income goes to EMIs", -5));
        if ("D".equals(s.emiBand())) f.add(new CapacityFactor("Over 40% of income goes to EMIs", -10));

        // Others relying on your income means a loss hurts more than just you.
        if ("C".equals(s.dependents())) f.add(new CapacityFactor("3 or more people depend on your income", -5));
        if (("B".equals(s.dependents()) || "C".equals(s.dependents()))
                && ("A".equals(s.termCover()) || "B".equals(s.termCover()) || "?".equals(s.termCover())))
            f.add(new CapacityFactor("Dependents without adequate term life cover", -5));
        if ("A".equals(s.healthCover()) || "?".equals(s.healthCover()))
            f.add(new CapacityFactor("No health insurance of your own", -5));

        if (p == ProfileType.RETIREE) {
            if (s.runwayYears() != null) {
                double r = s.runwayYears();
                int rd = r >= 25 ? 10 : r >= 15 ? 5 : r >= 8 ? -5 : -10;
                f.add(new CapacityFactor("Corpus expected to last about " + Math.round(r) + " years", rd));
            } else if (s.runwayAsked()) {
                f.add(new CapacityFactor("Corpus runway not known", -5));
            }
            if ("A".equals(s.retireeDebt())) f.add(new CapacityFactor("Large EMI still running in retirement", -10));
            if ("B".equals(s.retireeDebt())) f.add(new CapacityFactor("Small EMI still running in retirement", -3));
            if ("A".equals(s.healthFund()) || "?".equals(s.healthFund()))
                f.add(new CapacityFactor("No separate medical fund", -5));
            if ("D".equals(s.healthFund())) f.add(new CapacityFactor("Medical fund sized for a major event", 5));
        }
        return f;
    }

    /** Tolerance, capacity (with its factors) and the band — capacity only ever caps tolerance. */
    public RiskAssessment assessRisk(Map<String, Map<String, Object>> raw, ProfileType profile) {
        int tolerance = engine.categoryPercentage(raw, QuestionCategory.RISK);
        FinancialSnapshot snap = snapshot(raw, profile);
        List<CapacityFactor> factors = capacityFactors(snap);
        int sum = factors.stream().mapToInt(CapacityFactor::delta).sum();
        int capacity = Math.max(0, Math.min(100, sum));
        RiskBand band = RiskBand.values()[Math.min(bandIndex(tolerance), bandIndex(capacity))];
        return new RiskAssessment(tolerance, capacity, band, ALLOCATIONS.get(band), factors, snap);
    }

    public record RiskAssessment(int tolerance, int capacity, RiskBand band, Map<String, Integer> allocation,
                                 List<CapacityFactor> factors, FinancialSnapshot snapshot) {}

    public static int bandIndex(int score) {
        if (score < 20) return 0;
        if (score < 40) return 1;
        if (score < 60) return 2;
        if (score < 80) return 3;
        return 4;
    }
}
