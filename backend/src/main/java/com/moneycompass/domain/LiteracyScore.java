package com.moneycompass.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The persisted result of a scored session.
 *
 * <p>{@code total} and {@code categoryBreakdown} come from
 * LiteracyScoringService, a pure function of the answers. {@code narrativeJson}
 * is the only LLM-authored field. Keeping them apart is what makes the score
 * reproducible across refreshes and testable without a model.
 *
 * <p>{@code providerUsed} and {@code modelUsed} record who actually served the
 * call, which is the evidence that the fallback works.
 */
@Entity
@Table(name = "literacy_scores")
public class LiteracyScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, unique = true)
    private UUID sessionId;

    @Column(nullable = false)
    private int total;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "category_breakdown", nullable = false, columnDefinition = "jsonb")
    private Map<String, Integer> categoryBreakdown;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "narrative_json", columnDefinition = "jsonb")
    private Map<String, Object> narrativeJson;

    @Column(name = "provider_used", length = 32)
    private String providerUsed;

    @Column(name = "model_used", length = 128)
    private String modelUsed;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected LiteracyScore() {
        // for JPA
    }

    public LiteracyScore(UUID sessionId, int total, Map<String, Integer> categoryBreakdown) {
        this.sessionId = sessionId;
        this.total = total;
        this.categoryBreakdown = categoryBreakdown;
    }

    public void attachNarrative(Map<String, Object> narrativeJson, String providerUsed, String modelUsed) {
        this.narrativeJson = narrativeJson;
        this.providerUsed = providerUsed;
        this.modelUsed = modelUsed;
    }

    public Long getId() { return id; }
    public UUID getSessionId() { return sessionId; }
    public int getTotal() { return total; }
    public Map<String, Integer> getCategoryBreakdown() { return categoryBreakdown; }
    public Map<String, Object> getNarrativeJson() { return narrativeJson; }
    public String getProviderUsed() { return providerUsed; }
    public String getModelUsed() { return modelUsed; }
    public Instant getCreatedAt() { return createdAt; }
}
