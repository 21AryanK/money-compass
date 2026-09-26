package com.moneycompass.risk.dto;

import com.moneycompass.domain.RiskBand;
import com.moneycompass.engine.FinancialSnapshot;
import com.moneycompass.engine.SnapshotCalculator.CapacityFactor;
import com.moneycompass.narrative.DraftDto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @param riskBand          the band the answers calculated — never changed by the user's choice
 * @param activeBand        the band being planned around: the calculated one, or the user's pick
 * @param allocationPercent the allocation for the active band
 * @param capacityFactors   the signed factors that sum to the capacity score
 * @param narrative         the current draft of the rationale for the active band
 * @param drafts            every draft for the active band, oldest first
 */
public record RiskProfileResponse(
        UUID sessionId,
        int toleranceScore,
        int capacityScore,
        RiskBand riskBand,
        RiskBand activeBand,
        Map<String, Integer> allocationPercent,
        List<CapacityFactor> capacityFactors,
        FinancialSnapshot snapshot,
        RiskNarrative narrative,
        List<DraftDto> drafts,
        String providerUsed,
        String modelUsed,
        String disclaimer
) {}
