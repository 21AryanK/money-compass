package com.moneycompass.ai;

import com.moneycompass.common.AiUnavailableException;
import com.moneycompass.config.MoneyCompassProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Provider routing, one call at a time: try {@code moneycompass.ai.primary},
 * and on failure try {@code moneycompass.ai.fallback}, unless it is
 * {@code none}. Both attempts go through a named circuit breaker and retry
 * from Resilience4j so a struggling provider is given up on quickly rather
 * than retried into a timeout on every request.
 *
 * <p>This class, not Spring AI, is where "provider fallback" lives — worth
 * saying precisely, since it is easy to assume the framework does this for
 * you. Spring AI only supplies the interchangeable {@code ChatModel} beans
 * that {@link ChatClientConfig} wraps.
 *
 * <p>Prompts are passed as {@link Message} objects rather than through
 * {@code ChatClient.user(String)}: that path runs the text through a template
 * renderer, and prompts here legitimately contain JSON braces. Structured
 * output uses {@link BeanOutputConverter} directly so any reasoning a model
 * emits in {@code <think>} tags (qwen3, deepseek-r1) is stripped first.
 *
 * <p>Only the {@code ollama} branch of {@link #resolveClient} is wired today.
 * {@code openai} and {@code bedrock} are valid values for
 * {@code AI_PRIMARY}/{@code AI_FALLBACK} (see {@code MoneyCompassProperties}),
 * but selecting them fails clearly at call time until Phase 5 adds their
 * starters and a matching {@code ChatClient} bean.
 */
@Service
public class ResilientChatClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientChatClient.class);
    private static final Pattern THINKING = Pattern.compile("(?s)<think>.*?</think>");

    private final MoneyCompassProperties properties;
    private final ChatClient ollamaChatClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final String ollamaModel;
    private final String openaiModel;
    private final String bedrockModel;

    public ResilientChatClient(
            MoneyCompassProperties properties,
            ChatClient ollamaChatClient,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            @Value("${spring.ai.ollama.chat.options.model}") String ollamaModel,
            @Value("${OPENAI_MODEL:gpt-4o-mini}") String openaiModel,
            @Value("${BEDROCK_MODEL:meta.llama3-1-8b-instruct-v1:0}") String bedrockModel) {
        this.properties = properties;
        this.ollamaChatClient = ollamaChatClient;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.retryRegistry = retryRegistry;
        this.ollamaModel = ollamaModel;
        this.openaiModel = openaiModel;
        this.bedrockModel = bedrockModel;
    }

    /** One system and one user prompt at the configured temperature. See {@link #call(List, Class, Double)}. */
    public <T> AiCallResult<T> call(String systemPrompt, String userPrompt, Class<T> type) {
        return call(List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)), type, null);
    }

    /**
     * Sends a conversation to the primary provider, falling back on failure.
     * {@code type} is either {@code String.class} for free text, or a record
     * type for structured output.
     *
     * @param temperature overrides {@code AI_TEMPERATURE} for this call, or
     *                    null to use it — Regenerate raises it so a new draft
     *                    actually reads differently
     * @throws AiUnavailableException if the primary fails and the fallback is
     *         {@code none} or also fails
     */
    public <T> AiCallResult<T> call(List<Message> messages, Class<T> type, Double temperature) {
        String primary = properties.ai().primary();
        String fallback = properties.ai().fallback();

        long started = System.nanoTime();
        try {
            Raw<T> raw = executeWithResilience("ai-primary", resolveClient(primary), messages, type, temperature);
            return new AiCallResult<>(raw.content(), primary, modelFor(primary), elapsedMs(started), raw.tokens());
        } catch (Exception primaryFailure) {
            log.warn("AI primary provider [{}] failed: {}", primary, primaryFailure.toString());

            if ("none".equals(fallback)) {
                throw new AiUnavailableException(
                        "The '" + primary + "' provider failed and no fallback is configured (AI_FALLBACK=none). "
                                + primaryFailure.getMessage());
            }

            try {
                Raw<T> raw = executeWithResilience("ai-fallback", resolveClient(fallback), messages, type, temperature);
                return new AiCallResult<>(raw.content(), fallback, modelFor(fallback), elapsedMs(started), raw.tokens());
            } catch (Exception fallbackFailure) {
                log.error("AI fallback provider [{}] also failed: {}", fallback, fallbackFailure.toString());
                throw new AiUnavailableException(
                        "Both the primary provider '" + primary + "' and the fallback provider '"
                                + fallback + "' failed to respond.");
            }
        }
    }

    private record Raw<T>(T content, Integer tokens) {}

    private <T> Raw<T> executeWithResilience(String breakerName, ChatClient client, List<Message> messages,
                                             Class<T> type, Double temperature) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(breakerName);
        Retry retry = retryRegistry.retry(breakerName);

        Supplier<Raw<T>> call = () -> invoke(client, messages, type, temperature);
        Supplier<Raw<T>> resilientCall = Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, call));
        return resilientCall.get();
    }

    private <T> Raw<T> invoke(ChatClient client, List<Message> messages, Class<T> type, Double temperature) {
        BeanOutputConverter<T> converter = type == String.class ? null : new BeanOutputConverter<>(type);
        List<Message> prompt = converter == null ? messages : withFormatInstructions(messages, converter.getFormat());

        var request = client.prompt().messages(prompt);
        if (temperature != null) {
            request = request.options(ChatOptions.builder().temperature(temperature));
        }
        ChatResponse response = request.call().chatResponse();
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("The model returned an empty response");
        }
        String text = stripThinking(response.getResult().getOutput().getText());
        Integer tokens = response.getMetadata() == null || response.getMetadata().getUsage() == null
                ? null : response.getMetadata().getUsage().getTotalTokens();

        T content = converter == null ? type.cast(text) : converter.convert(text);
        if (content == null) {
            throw new IllegalStateException("The model's response could not be parsed");
        }
        return new Raw<>(content, tokens == null || tokens == 0 ? null : tokens);
    }

    /** Appends the JSON-schema instructions to the last user message. */
    private static List<Message> withFormatInstructions(List<Message> messages, String format) {
        List<Message> out = new ArrayList<>(messages);
        for (int i = out.size() - 1; i >= 0; i--) {
            if (out.get(i) instanceof UserMessage user) {
                out.set(i, new UserMessage(user.getText() + "\n\n" + format));
                return out;
            }
        }
        out.add(new UserMessage(format));
        return out;
    }

    static String stripThinking(String text) {
        return text == null ? "" : THINKING.matcher(text).replaceAll("").trim();
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private ChatClient resolveClient(String provider) {
        return switch (provider) {
            case "ollama" -> ollamaChatClient;
            default -> throw new AiUnavailableException(
                    "AI provider '" + provider + "' is not wired yet. Phase 5 adds openai and bedrock; until "
                            + "then set AI_PRIMARY=ollama and AI_FALLBACK to ollama or none in infra/.env.");
        };
    }

    /** The model name a provider is configured with, for display. */
    public String modelFor(String provider) {
        return switch (provider) {
            case "ollama" -> ollamaModel;
            case "openai" -> openaiModel;
            case "bedrock" -> bedrockModel;
            default -> "unknown";
        };
    }

    /** The configured primary provider and its model, for labelling before a call has been made. */
    public String primaryProvider() {
        return properties.ai().primary();
    }
}
