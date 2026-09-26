package com.moneycompass.risk;

import com.moneycompass.common.ConflictException;
import com.moneycompass.domain.RiskBand;
import com.moneycompass.domain.RiskProfile;
import com.moneycompass.engine.FinancialSnapshot;
import com.moneycompass.engine.SessionContext;
import com.moneycompass.engine.SnapshotCalculator;
import com.moneycompass.narrative.DraftDto;
import com.moneycompass.narrative.NarrativeDraft;
import com.moneycompass.narrative.NarrativeService;
import com.moneycompass.repo.RiskProfileRepository;
import com.moneycompass.risk.dto.RiskNarrative;
import com.moneycompass.risk.dto.RiskProfileResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Same compute-if-absent, generate-narrative-if-missing shape as
 * {@code ScoreService}, plus two things the Risk screen needs:
 *
 * <ul>
 *   <li>the user can choose to plan around a different band than the one
 *       calculated ({@link #selectBand}); the calculated band is kept as is,
 *       and the allocation, rationale and plan follow the chosen one;</li>
 *   <li>the rationale has its own draft history per band, since it describes
 *       whichever band is active.</li>
 * </ul>
 */
@Service
public class RiskService {

    private static final String DISCLAIMER =
            "Educational content only, written in part by an AI model. This is not financial advice.";
    static final int MAX_DRAFTS = 10;

    private final SessionContext sessionContext;
    private final RiskProfileRepository riskProfileRepository;
    private final SnapshotCalculator calculator;
    private final NarrativeService narrativeService;

    public RiskService(SessionContext sessionContext, RiskProfileRepository riskProfileRepository,
                       SnapshotCalculator calculator, NarrativeService narrativeService) {
        this.sessionContext = sessionContext;
        this.riskProfileRepository = riskProfileRepository;
        this.calculator = calculator;
        this.narrativeService = narrativeService;
    }

    @Transactional
    public RiskProfileResponse computeOrFetch(UUID sessionId, UUID userId) {
        SessionContext.Loaded ctx = sessionContext.load(sessionId, userId);
        RiskProfile profile = ensureProfile(ctx);
        ensureNarrative(ctx, profile);
        return toResponse(ctx, profile);
    }

    /** Plans around {@code band} from now on; pass the calculated band (or null) to go back to it. */
    @Transactional
    public RiskProfileResponse selectBand(UUID sessionId, UUID userId, RiskBand band) {
        SessionContext.Loaded ctx = sessionContext.load(sessionId, userId);
        RiskProfile profile = ensureProfile(ctx);
        profile.selectBand(band == null ? profile.getRiskBand() : band);
        ensureNarrative(ctx, profile);
        riskProfileRepository.save(profile);
        return toResponse(ctx, profile);
    }

    /** A new draft of the rationale for the active band, taking a different angle from the earlier ones. */
    @Transactional
    public RiskProfileResponse regenerate(UUID sessionId, UUID userId) {
        SessionContext.Loaded ctx = sessionContext.load(sessionId, userId);
        RiskProfile profile = riskProfileRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new ConflictException("Assess risk for this session before regenerating its rationale"));
        RiskBand band = profile.getActiveBand();
        profile.addDraft(band, narrativeService.riskDraft(inputs(ctx, profile), profile.draftsFor(band)));
        profile.trimDrafts(band, MAX_DRAFTS);
        riskProfileRepository.save(profile);
        return toResponse(ctx, profile);
    }

    private RiskProfile ensureProfile(SessionContext.Loaded ctx) {
        UUID sessionId = ctx.session().getId();
        RiskProfile profile = riskProfileRepository.findBySessionId(sessionId).orElse(null);
        if (profile == null) {
            SnapshotCalculator.RiskAssessment r = calculator.assessRisk(ctx.answers(), ctx.profile());
            profile = new RiskProfile(sessionId, r.tolerance(), r.capacity(), r.band(), r.allocation());
            profile.setCapacityFactors(r.factors());
            return riskProfileRepository.save(profile);
        }
        if (profile.getCapacityFactors().isEmpty()) {
            // Saved before capacity was itemised: recompute under today's rules
            // so the factors shown always add up to the number shown.
            SnapshotCalculator.RiskAssessment r = calculator.assessRisk(ctx.answers(), ctx.profile());
            profile.reassess(r.tolerance(), r.capacity(), r.band(), r.allocation(), r.factors());
            riskProfileRepository.save(profile);
        }
        return profile;
    }

    private void ensureNarrative(SessionContext.Loaded ctx, RiskProfile profile) {
        RiskBand band = profile.getActiveBand();
        if (!profile.draftsFor(band).isEmpty()) return;
        if (band == profile.getRiskBand() && profile.getNarrativeJson() != null && profile.getNarrativeDraftsMap().isEmpty()) {
            // A rationale from before draft history existed: keep it as draft 1.
            profile.addDraft(band, new NarrativeDraft(profile.getNarrativeJson(), profile.getProviderUsed(), profile.getModelUsed(),
                    0, null, Instant.now().toString(), List.of(), null));
        } else {
            profile.addDraft(band, narrativeService.riskDraft(inputs(ctx, profile), List.of()));
        }
        riskProfileRepository.save(profile);
    }

    private NarrativeService.RiskInputs inputs(SessionContext.Loaded ctx, RiskProfile profile) {
        FinancialSnapshot snap = calculator.snapshot(ctx.answers(), ctx.profile());
        RiskBand band = profile.getActiveBand();
        return new NarrativeService.RiskInputs(profile.getToleranceScore(), profile.getCapacityScore(), band,
                SnapshotCalculator.ALLOCATIONS.get(band), profile.getCapacityFactors(), snap, ctx.profile(),
                calculator.suggestedMonthly(snap));
    }

    private RiskProfileResponse toResponse(SessionContext.Loaded ctx, RiskProfile profile) {
        RiskBand band = profile.getActiveBand();
        List<NarrativeDraft> drafts = profile.draftsFor(band);
        NarrativeDraft current = drafts.isEmpty() ? null : drafts.getLast();
        return new RiskProfileResponse(
                profile.getSessionId(),
                profile.getToleranceScore(),
                profile.getCapacityScore(),
                profile.getRiskBand(),
                band,
                SnapshotCalculator.ALLOCATIONS.get(band),
                profile.getCapacityFactors(),
                calculator.snapshot(ctx.answers(), ctx.profile()),
                current == null ? null : fromMap(current.content()),
                drafts.stream().map(DraftDto::of).toList(),
                current == null ? null : current.provider(),
                current == null ? null : current.model(),
                DISCLAIMER);
    }

    @SuppressWarnings("unchecked")
    static RiskNarrative fromMap(Map<String, Object> map) {
        if (map == null) return null;
        return new RiskNarrative(
                (String) map.get("rationale"),
                (List<String>) map.get("considerations"),
                (List<String>) map.get("avoid"));
    }
}
