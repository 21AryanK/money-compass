package com.moneycompass.health;

/**
 * @param provider the configured provider name: {@code ollama}, {@code openai}, {@code bedrock} or {@code none}
 * @param model    the model that provider is configured to use
 * @param up       whether a live probe (or, for an unwired provider, its wiring status) succeeded
 * @param probeMs  how long the live probe took, or null when nothing was probed
 */
public record ProviderHealth(String provider, String model, boolean up, Long probeMs) {}
