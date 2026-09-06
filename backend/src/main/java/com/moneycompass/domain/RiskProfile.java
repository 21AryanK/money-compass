package com.moneycompass.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Tolerance, capacity, the resulting band, and the allocation.
 *
 * <p>{@code allocation} is a JSONB map of asset class to whole-number percent,
 * for example {@code {"equity":40,"debt":40,"gold":10,"cash":10}}. It always
 * sums to 100, enforced by unit test rather than by a database constraint.
 */
@Entity
@Table(name = "risk_profiles")
public class RiskProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, unique = true)
    private UUID sessionId;

    @Column(name = "tolerance_score", nullable = false)
    private int toleranceScore;

    @Column(name = "capacity_score", nullable = false)
    private int capacityScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_band", nullable = false, length = 16)
    private RiskBand riskBand;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Integer> allocation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "narrative_json", columnDefinition = "jsonb")
    private Map<String, Object> narrativeJson;

    @Column(name = "provider_used", length = 32)
    private String providerUsed;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected RiskProfile() {
        // for JPA
    }

    public RiskProfile(UUID sessionId, int toleranceScore, int capacityScore,
                       RiskBand riskBand, Map<String, Integer> allocation) {
        this.sessionId = sessionId;
        this.toleranceScore = toleranceScore;
        this.capacityScore = capacityScore;
        this.riskBand = riskBand;
        this.allocation = allocation;
    }

    public void attachNarrative(Map<String, Object> narrativeJson, String providerUsed) {
        this.narrativeJson = narrativeJson;
        this.providerUsed = providerUsed;
    }

    public Long getId() { return id; }
    public UUID getSessionId() { return sessionId; }
    public int getToleranceScore() { return toleranceScore; }
    public int getCapacityScore() { return capacityScore; }
    public RiskBand getRiskBand() { return riskBand; }
    public Map<String, Integer> getAllocation() { return allocation; }
    public Map<String, Object> getNarrativeJson() { return narrativeJson; }
    public String getProviderUsed() { return providerUsed; }
    public Instant getCreatedAt() { return createdAt; }
}
