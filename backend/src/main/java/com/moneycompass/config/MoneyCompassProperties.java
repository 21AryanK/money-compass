package com.moneycompass.config;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Everything under the {@code moneycompass.*} prefix, bound once and validated
 * at startup.
 *
 * <p>The point of validating here is that a missing or too-short JWT_SECRET
 * fails the context refresh with a message naming the property, rather than
 * throwing a cryptographic exception on the first login attempt in production.
 */
@Validated
@ConfigurationProperties(prefix = "moneycompass")
public record MoneyCompassProperties(
        @NotNull Ai ai,
        @NotNull Questionnaire questionnaire,
        @NotNull Security security,
        @NotNull Cors cors
) {

    public record Ai(
            /** Which provider is tried first. */
            @Pattern(regexp = "ollama|openai") String primary,
            /** Which provider catches a primary failure. */
            @Pattern(regexp = "bedrock|ollama|none") String fallback,
            @DecimalMin("0.0") @DecimalMax("2.0") double temperature,
            @Min(64) @Max(4096) int maxTokens
    ) {}

    public record Questionnaire(
            @Min(1) @Max(50) int maxQuestions
    ) {}

    public record Security(
            /**
             * HMAC-SHA256 signing key. 32 bytes is the minimum for HS256; a
             * shorter key is rejected by Nimbus at runtime, so it is rejected
             * here instead where the error is readable.
             */
            @NotBlank @Size(min = 32, message = "JWT_SECRET must be at least 32 characters") String jwtSecret,
            @Min(1) @Max(720) int jwtTtlHours
    ) {}

    public record Cors(
            @NotEmpty List<String> allowedOrigins
    ) {}
}
