package com.moneycompass;

import com.moneycompass.config.MoneyCompassProperties;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test, no Spring context. Proves the guard rails on the config
 * surface actually fire, since a silently accepted 8 character JWT secret is
 * the kind of thing that only surfaces in production.
 */
class MoneyCompassPropertiesValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    private MoneyCompassProperties props(String secret, String primary, String fallback) {
        return new MoneyCompassProperties(
                new MoneyCompassProperties.Ai(primary, fallback, 0.2, 800),
                new MoneyCompassProperties.Questionnaire(15),
                new MoneyCompassProperties.Security(secret, 24),
                new MoneyCompassProperties.Cors(List.of("http://localhost:4200")));
    }

    @Test
    void acceptsAValidConfiguration() {
        assertThat(validator.validate(
                props("a-secret-that-is-at-least-32-characters", "ollama", "bedrock")))
                .isEmpty();
    }

    @Test
    void rejectsAShortJwtSecret() {
        assertThat(validator.validate(props("too-short", "ollama", "none")))
                .isNotEmpty();
    }

    @Test
    void rejectsAnUnknownPrimaryProvider() {
        assertThat(validator.validate(
                props("a-secret-that-is-at-least-32-characters", "anthropic", "none")))
                .isNotEmpty();
    }
}
