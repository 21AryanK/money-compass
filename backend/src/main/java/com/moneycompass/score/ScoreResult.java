package com.moneycompass.score;

import java.util.Map;

/**
 * The pure-Java half of a literacy score: no AI involved, reproducible from
 * the answers alone. A {@code null} category value means that category wasn't
 * assessed for this profile, which is different from scoring zero on it.
 */
public record ScoreResult(int total, Map<String, Integer> categoryBreakdown) {}
