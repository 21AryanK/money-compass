package com.moneycompass.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One answer in one session.
 *
 * <p>{@code value} is JSONB rather than a string so that all four question
 * types share a column: {@code {"selected":"B"}}, {@code {"selected":["A","C"]}},
 * {@code {"number":6}}, {@code {"scale":4}}.
 */
@Entity
@Table(name = "answers")
public class Answer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "question_code", nullable = false, length = 64)
    private String questionCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> value;

    @Column(name = "answered_at", nullable = false, insertable = false, updatable = false)
    private Instant answeredAt;

    protected Answer() {
        // for JPA
    }

    public Answer(UUID sessionId, String questionCode, Map<String, Object> value) {
        this.sessionId = sessionId;
        this.questionCode = questionCode;
        this.value = value;
    }

    public Long getId() { return id; }
    public UUID getSessionId() { return sessionId; }
    public String getQuestionCode() { return questionCode; }
    public Map<String, Object> getValue() { return value; }
    public Instant getAnsweredAt() { return answeredAt; }

    public void setValue(Map<String, Object> value) { this.value = value; }
}
