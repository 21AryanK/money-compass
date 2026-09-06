package com.moneycompass;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A real Postgres 16 for integration tests.
 *
 * <p>Real rather than H2 because the schema uses JSONB and {@code text[]},
 * neither of which H2 emulates faithfully. A test that passes on H2 and fails
 * on Postgres is worse than no test.
 *
 * <p>{@code @ServiceConnection} wires the container's JDBC URL, username and
 * password into the context automatically, so no {@code @DynamicPropertySource}
 * block is needed.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestContainer {

    @Bean
    @ServiceConnection
    @SuppressWarnings("resource")
    public PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:16")
                .withReuse(true);
    }
}
