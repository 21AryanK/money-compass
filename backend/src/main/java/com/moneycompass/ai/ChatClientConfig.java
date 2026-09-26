package com.moneycompass.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wraps each provider's {@code ChatModel} in a {@code ChatClient}.
 *
 * <p>Only Ollama is wired today: it is the only starter on the classpath (see
 * the AI model starters comment in {@code pom.xml}), so Spring Boot
 * autoconfigures exactly one {@code OllamaChatModel} bean from
 * {@code spring.ai.ollama.*} with no ambiguity to resolve. Adding OpenAI or
 * Bedrock in Phase 5 means adding their starter here, injecting their
 * {@code ChatModel} type the same way, and registering the result under a new
 * qualifier that {@link ResilientChatClient#resolveClient} knows about.
 *
 * <p>{@code OLLAMA_DISABLE_THINKING=true} turns off the hidden reasoning pass
 * of "thinking" models such as qwen3 or deepseek-r1. Their answers are fine
 * either way ({@code <think>} blocks are stripped), but thinking can make
 * every explanation and chat reply take minutes on a laptop. Off by default,
 * because models without a thinking mode don't need it.
 */
@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient ollamaChatClient(OllamaChatModel ollamaChatModel,
                                       @Value("${OLLAMA_DISABLE_THINKING:false}") boolean disableThinking) {
        ChatClient.Builder builder = ChatClient.builder(ollamaChatModel);
        if (disableThinking) {
            builder.defaultOptions(OllamaChatOptions.builder().disableThinking());
        }
        return builder.build();
    }
}
