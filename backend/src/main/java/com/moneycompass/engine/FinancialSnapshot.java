package com.moneycompass.engine;

import com.moneycompass.domain.ProfileType;

import java.util.List;

/**
 * The user's own numbers, in rupees where possible, which every results
 * screen, narrative and assistant answer personalises from. Anything the user
 * didn't know stays {@code null} (or is flagged, like {@link #rateKnown})
 * rather than being invented, so the screens can say so.
 *
 * <p>Option-code fields hold the code chosen, {@code "?"} for "I don't know",
 * or {@code null} if the question was never asked.
 */
public record FinancialSnapshot(
        ProfileType profile,
        Integer age,
        Integer yearsToRetire,
        /** Monthly take-home, ₹. */
        Double income,
        double savingsRate,
        boolean rateKnown,
        /** Estimated monthly spending: income minus saving. */
        Double spend,
        /** Monthly saving, ₹. */
        Double saving,
        Double efMonths,
        int efTargetMonths,
        Double efTarget,
        Double efHave,
        Double efGap,
        /** true / false, or null when "I don't know". */
        Boolean highInterestDebt,
        String payoffPlan,
        String horizon,
        Integer horizonYears,
        Integer dropScale,
        String tracking,
        String autoSave,
        String emiBand,
        String dependents,
        String termCover,
        String healthCover,
        Double corpusMultiple,
        Double runwayYears,
        boolean runwayAsked,
        String retireeDebt,
        String healthFund,
        String nomination,
        List<MissedTopic> missedTopics
) {

    public record MissedTopic(String code, String topic, boolean unknown) {}

    public boolean incomeKnown() {
        return income != null && income > 0;
    }
}
