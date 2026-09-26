package com.moneycompass.plan;

import com.moneycompass.domain.RiskBand;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the investment plan screen and the PDF report show.
 *
 * @param suggestedMonthly what's left for investing once debt and the
 *                         emergency fund have had their share
 * @param mixes            breakdown cards keyed "fund", "direct", "debt",
 *                         "gold", "cash"; a key is absent when that slice is empty
 */
public record PlanResponse(
        double monthly,
        int years,
        Set<String> vehicles,
        double stepUp,
        RiskBand band,
        Map<String, Integer> allocation,
        double suggestedMonthly,
        Double income,
        double savingsRate,
        boolean rateKnown,
        InvestmentPlanner.Priorities priorities,
        InvestmentPlanner.Projection projection,
        Map<String, InvestmentPlanner.MixView> mixes,
        double inflationPct
) {}
