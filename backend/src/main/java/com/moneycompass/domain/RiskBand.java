package com.moneycompass.domain;

/**
 * Ordered from least to most risk. Ordinal order is load bearing: the
 * combination rule in Phase 6 is {@code band = min(toleranceBand, capacityBand)},
 * so capacity can only ever cap tolerance, never raise it.
 */
public enum RiskBand {
    CONSERVATIVE,
    MODERATE,
    BALANCED,
    GROWTH,
    AGGRESSIVE
}
