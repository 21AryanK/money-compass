package com.moneycompass.risk;

import com.moneycompass.domain.RiskBand;
import com.moneycompass.engine.SnapshotCalculator.CapacityFactor;

import java.util.List;
import java.util.Map;

/** The deterministic half of a risk profile: tolerance, itemised capacity, the combined band, and its allocation. */
public record RiskResult(int toleranceScore, int capacityScore, RiskBand riskBand, Map<String, Integer> allocation,
                         List<CapacityFactor> capacityFactors) {}
