package com.moneycompass;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the migration applied and that Hibernate's {@code ddl-auto: validate}
 * agreed with it. If the entity mappings and V1__init.sql disagree, the context
 * fails to start and this test fails before any assertion runs.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
@TestPropertySource(properties = {
        "moneycompass.security.jwt-secret=test-secret-key-for-integration-tests-only-32b",
        "spring.config.import="
})
class FlywaySchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void everyTableFromTheSpecExists() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public'
                """, String.class);

        assertThat(tables).contains(
                "users", "questions", "question_rules", "sessions",
                "answers", "literacy_scores", "risk_profiles");
    }

    @Test
    void profileTypeCheckConstraintRejectsUnknownValues() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint WHERE conname = 'users_profile_type_chk'
                """, Integer.class)).isEqualTo(1);
    }
}
