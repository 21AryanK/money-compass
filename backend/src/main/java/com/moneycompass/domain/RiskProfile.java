package com.moneycompass.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.moneycompass.engine.SnapshotCalculator.CapacityFactor;
import com.moneycompass.narrative.NarrativeDraft;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    @Column(name = "model_used", length = 128)
    private String modelUsed;

    /** The signed factors that sum to {@link #capacityScore}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "capacity_factors", nullable = false, columnDefinition = "jsonb")
    private List<CapacityFactor> capacityFactors = new ArrayList<>();

    /** The band the user chose to plan around instead of {@link #riskBand}; null means use the calculated one. */
    @Enumerated(EnumType.STRING)
    @Column(name = "selected_band", length = 16)
    private RiskBand selectedBand;

    /** Drafts of the rationale per band, oldest first, since the rationale describes whichever band is active. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "narrative_drafts", nullable = false, columnDefinition = "jsonb")
    private Map<String, List<NarrativeDraft>> narrativeDrafts = new LinkedHashMap<>();

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

    /** Replaces the deterministic half, e.g. when a row from before itemised capacity is recomputed. */
    public void reassess(int toleranceScore, int capacityScore, RiskBand riskBand, Map<String, Integer> allocation,
                         List<CapacityFactor> capacityFactors) {
        this.toleranceScore = toleranceScore;
        this.capacityScore = capacityScore;
        this.riskBand = riskBand;
        this.allocation = allocation;
        this.capacityFactors = capacityFactors;
    }

    public void setCapacityFactors(List<CapacityFactor> capacityFactors) { this.capacityFactors = capacityFactors; }

    /** Plan around {@code band} instead of the calculated one; the calculated band itself is never changed. */
    public void selectBand(RiskBand band) {
        this.selectedBand = band == riskBand ? null : band;
    }

    /** The band everything downstream — allocation, rationale, plan — should follow. */
    public RiskBand getActiveBand() {
        return selectedBand != null ? selectedBand : riskBand;
    }

    public List<NarrativeDraft> draftsFor(RiskBand band) {
        return narrativeDrafts == null ? List.of() : narrativeDrafts.getOrDefault(band.name(), List.of());
    }

    public Map<String, List<NarrativeDraft>> getNarrativeDraftsMap() {
        return narrativeDrafts == null ? Map.of() : narrativeDrafts;
    }

    /** Keeps only the newest {@code max} drafts for a band. */
    public void trimDrafts(RiskBand band, int max) {
        List<NarrativeDraft> drafts = draftsFor(band);
        if (drafts.size() <= max) return;
        Map<String, List<NarrativeDraft>> all = new LinkedHashMap<>(getNarrativeDraftsMap());
        all.put(band.name(), new ArrayList<>(drafts.subList(drafts.size() - max, drafts.size())));
        this.narrativeDrafts = all;
    }

    public void addDraft(RiskBand band, NarrativeDraft draft) {
        Map<String, List<NarrativeDraft>> all = new LinkedHashMap<>(narrativeDrafts == null ? Map.of() : narrativeDrafts);
        List<NarrativeDraft> drafts = new ArrayList<>(all.getOrDefault(band.name(), List.of()));
        drafts.add(draft);
        all.put(band.name(), drafts);
        this.narrativeDrafts = all;
        this.narrativeJson = draft.content();
        this.providerUsed = draft.provider();
        this.modelUsed = draft.model();
    }

    public Long getId() { return id; }
    public String getModelUsed() { return modelUsed; }
    public List<CapacityFactor> getCapacityFactors() { return capacityFactors == null ? List.of() : capacityFactors; }
    public RiskBand getSelectedBand() { return selectedBand; }
    public UUID getSessionId() { return sessionId; }
    public int getToleranceScore() { return toleranceScore; }
    public int getCapacityScore() { return capacityScore; }
    public RiskBand getRiskBand() { return riskBand; }
    public Map<String, Integer> getAllocation() { return allocation; }
    public Map<String, Object> getNarrativeJson() { return narrativeJson; }
    public String getProviderUsed() { return providerUsed; }
    public Instant getCreatedAt() { return createdAt; }
}
