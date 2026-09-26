package com.moneycompass.health;

/**
 * @param circuitState the "ai-primary" circuit breaker's state:
 *                      {@code CLOSED}, {@code OPEN} or {@code HALF_OPEN}
 * @param checkedAt    when this check ran
 */
public record AiHealthResponse(ProviderHealth primary, ProviderHealth fallback, String circuitState,
                               java.time.Instant checkedAt) {}
