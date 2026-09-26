package com.moneycompass.assistant;

import com.moneycompass.domain.ProfileType;
import com.moneycompass.domain.RiskBand;
import com.moneycompass.engine.FinancialSnapshot;
import com.moneycompass.engine.SnapshotCalculator.CapacityFactor;
import com.moneycompass.engine.SnapshotCalculator.Waterfall;
import com.moneycompass.plan.InvestmentPlanner.Priorities;

import java.util.List;
import java.util.Map;

/**
 * Everything the assistant knows about the person it's talking to: their
 * results exactly as the other screens show them, plus the plan inputs they
 * currently have on screen.
 *
 * @param calculatedBand the band the answers produced
 * @param activeBand     the band being planned around (may be the user's pick)
 * @param planMonthly    the monthly amount on the plan screen, or the suggested one
 * @param planYears      the plan horizon on screen
 */
public record AssistantContext(
        ProfileType profile,
        int answered,
        int total,
        Map<String, Integer> breakdown,
        FinancialSnapshot snapshot,
        Waterfall flow,
        Priorities priorities,
        int tolerance,
        int capacity,
        RiskBand calculatedBand,
        RiskBand activeBand,
        Map<String, Integer> allocation,
        List<CapacityFactor> factors,
        double planMonthly,
        int planYears
) {}
