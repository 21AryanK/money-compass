package com.moneycompass.health;

import com.moneycompass.config.MoneyCompassProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Backs {@code GET /api/health/ai}. Reports the currently configured primary
 * and fallback providers plus the "ai-primary" circuit breaker's state, which
 * reflects real recent call outcomes rather than a synthetic probe.
 *
 * <p>Ollama is the only provider actually pinged: a cheap {@code GET /api/tags}
 * against the local server, which is exactly the check that answers "did
 * pasting my Ollama path work?" without spending a token. OpenAI and Bedrock
 * report {@code up: false} unconditionally until Phase 5 wires them — they are
 * not called at all today, so "up" cannot mean anything else yet.
 */
@Service
public class AiHealthService {

    private static final Logger log = LoggerFactory.getLogger(AiHealthService.class);

    private final MoneyCompassProperties properties;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RestClient restClient;
    private final String ollamaBaseUrl;
    private final String ollamaModel;
    private final String openaiModel;
    private final String bedrockModel;

    public AiHealthService(
            MoneyCompassProperties properties,
            CircuitBreakerRegistry circuitBreakerRegistry,
            @Value("${OLLAMA_BASE_URL:http://localhost:11434}") String ollamaBaseUrl,
            @Value("${spring.ai.ollama.chat.options.model}") String ollamaModel,
            @Value("${OPENAI_MODEL:gpt-4o-mini}") String openaiModel,
            @Value("${BEDROCK_MODEL:meta.llama3-1-8b-instruct-v1:0}") String bedrockModel) {
        this.properties = properties;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.ollamaModel = ollamaModel;
        this.openaiModel = openaiModel;
        this.bedrockModel = bedrockModel;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        // Short and fixed: this is a liveness probe for a health endpoint, not
        // a real call, and it must never make the page that shows it feel slow.
        requestFactory.setConnectTimeout(2000);
        requestFactory.setReadTimeout(2000);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    public AiHealthResponse check() {
        ProviderHealth primary = probe(properties.ai().primary());
        ProviderHealth fallback = probe(properties.ai().fallback());
        String circuitState = circuitBreakerRegistry.circuitBreaker("ai-primary").getState().name();
        return new AiHealthResponse(primary, fallback, circuitState, java.time.Instant.now());
    }

    private ProviderHealth probe(String provider) {
        return switch (provider) {
            case "ollama" -> {
                long started = System.nanoTime();
                boolean up = pingOllama();
                yield new ProviderHealth("ollama", ollamaModel, up, (System.nanoTime() - started) / 1_000_000);
            }
            // A no-op fallback trivially "works": there is nothing to fail.
            case "none" -> new ProviderHealth("none", "-", true, null);
            case "openai" -> new ProviderHealth("openai", openaiModel, false, null);
            case "bedrock" -> new ProviderHealth("bedrock", bedrockModel, false, null);
            default -> new ProviderHealth(provider, "unknown", false, null);
        };
    }

    private boolean pingOllama() {
        try {
            restClient.get().uri(ollamaBaseUrl + "/api/tags").retrieve().toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            log.debug("Ollama health probe against {} failed: {}", ollamaBaseUrl, e.toString());
            return false;
        }
    }
}
