package com.moneycompass.assistant;

import java.util.List;

/**
 * @param reply     the assistant's answer
 * @param followUps suggested next questions
 * @param provider  who wrote it; {@code rules} when no model answered and the
 *                  grounded rules answer was returned as-is
 * @param tokens    tokens the provider reported, or null
 */
public record ChatResponse(String reply, List<String> followUps, String provider, String model, long latencyMs,
                           Integer tokens) {}
