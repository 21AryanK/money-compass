package com.moneycompass.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

/**
 * One branching rule: when {@link #questionCode} has been answered and
 * {@link #condition} matches that answer, {@link #nextQuestionCode} becomes
 * eligible to be served next.
 *
 * <p>Condition shape: {@code {"question":"has_emergency_fund","equals":"NO"}}.
 * Evaluated in Java by the questionnaire engine, not in SQL, so the rule
 * vocabulary can grow without a migration.
 */
@Entity
@Table(name = "question_rules")
public class QuestionRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "question_code", nullable = false, length = 64)
    private String questionCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "condition", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> condition;

    @Column(name = "next_question_code", nullable = false, length = 64)
    private String nextQuestionCode;

    /** Lower runs first. */
    @Column(nullable = false)
    private int priority = 100;

    protected QuestionRule() {
        // for JPA
    }

    public Long getId() { return id; }
    public String getQuestionCode() { return questionCode; }
    public Map<String, Object> getCondition() { return condition; }
    public String getNextQuestionCode() { return nextQuestionCode; }
    public int getPriority() { return priority; }
}
