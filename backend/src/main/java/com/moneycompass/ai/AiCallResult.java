package com.moneycompass.ai;

/**
 * The content a provider returned, plus which provider and model actually
 * served it. The latter two are persisted alongside the content (see
 * {@code LiteracyScore.providerUsed} / {@code modelUsed}) because that is the
 * only evidence, short of pulling the logs, that a fallback ever fired.
 *
 * @param latencyMs wall-clock time of the successful call, retries included
 * @param tokens    total tokens the provider reported, or null if it didn't
 */
public record AiCallResult<T>(T content, String provider, String model, long latencyMs, Integer tokens) {}
