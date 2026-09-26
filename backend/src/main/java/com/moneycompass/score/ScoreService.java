package com.moneycompass.score;

import com.moneycompass.common.ConflictException;
import com.moneycompass.domain.LiteracyScore;
import com.moneycompass.engine.AssessmentEngine;
import com.moneycompass.engine.FinancialSnapshot;
import com.moneycompass.engine.SessionContext;
import com.moneycompass.engine.SnapshotCalculator;
import com.moneycompass.narrative.DraftDto;
import com.moneycompass.narrative.NarrativeDraft;
import com.moneycompass.narrative.NarrativeService;
import com.moneycompass.repo.LiteracyScoreRepository;
import com.moneycompass.score.dto.LiteracyScoreResponse;
import com.moneycompass.score.dto.Narrative;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates the two halves of a literacy score: the deterministic total
 * and breakdown from {@link AssessmentEngine}, and the AI-written
 * {@link Narrative} on top of it, kept as a history of drafts.
 *
 * <p>The two are computed and persisted separately and idempotently. If the
 * deterministic part already exists it is never recomputed; if there is no
 * narrative draft yet, one is generated. {@link #regenerate} appends a new
 * draft rather than replacing the last, so the user can page back through
 * them.
 */
@Service
public class ScoreService {

    private static final String DISCLAIMER =
            "Educational content only, written in part by an AI model. This is not financial advice.";
    /** Drafts kept per score; older ones drop off the front. */
    static final int MAX_DRAFTS = 10;

    private final SessionContext sessionContext;
    private final LiteracyScoreRepository literacyScoreRepository;
    private final AssessmentEngine engine;
    private final SnapshotCalculator calculator;
    private final NarrativeService narrativeService;

    public ScoreService(SessionContext sessionContext, LiteracyScoreRepository literacyScoreRepository,
                        AssessmentEngine engine, SnapshotCalculator calculator, NarrativeService narrativeService) {
        this.sessionContext = sessionContext;
        this.literacyScoreRepository = literacyScoreRepository;
        this.engine = engine;
        this.calculator = calculator;
        this.narrativeService = narrativeService;
    }

    @Transactional
    public LiteracyScoreResponse computeOrFetch(UUID sessionId, UUID userId) {
        SessionContext.Loaded ctx = sessionContext.load(sessionId, userId);
        LiteracyScore score = ensureScore(ctx);
        if (score.getNarrativeDrafts().isEmpty()) {
            if (score.getNarrativeJson() != null) {
                // A narrative from before draft history existed: keep it as draft 1.
                score.addDraft(new NarrativeDraft(score.getNarrativeJson(), score.getProviderUsed(), score.getModelUsed(),
                        0, null, Instant.now().toString(), List.of(), null));
            } else {
                score.addDraft(narrativeService.scoreDraft(inputs(ctx, score), List.of()));
            }
            literacyScoreRepository.save(score);
        }
        return toResponse(ctx, score);
    }

    /** Writes a new draft that takes a different angle from the earlier ones, and makes it current. */
    @Transactional
    public LiteracyScoreResponse regenerate(UUID sessionId, UUID userId) {
        SessionContext.Loaded ctx = sessionContext.load(sessionId, userId);
        LiteracyScore score = literacyScoreRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new ConflictException("Score this session before regenerating its explanation"));
        List<NarrativeDraft> previous = score.getNarrativeDrafts();
        score.addDraft(narrativeService.scoreDraft(inputs(ctx, score), previous));
        score.trimDrafts(MAX_DRAFTS);
        literacyScoreRepository.save(score);
        return toResponse(ctx, score);
    }

    private LiteracyScore ensureScore(SessionContext.Loaded ctx) {
        return literacyScoreRepository.findBySessionId(ctx.session().getId()).orElseGet(() -> {
            ScoreResult result = engine.score(ctx.answers());
            return literacyScoreRepository.save(
                    new LiteracyScore(ctx.session().getId(), result.total(), result.categoryBreakdown()));
        });
    }

    private NarrativeService.ScoreInputs inputs(SessionContext.Loaded ctx, LiteracyScore score) {
        FinancialSnapshot snap = calculator.snapshot(ctx.answers(), ctx.profile());
        return new NarrativeService.ScoreInputs(score.getTotal(), score.getCategoryBreakdown(), ctx.profile(),
                snap, calculator.waterfall(snap));
    }

    private LiteracyScoreResponse toResponse(SessionContext.Loaded ctx, LiteracyScore score) {
        FinancialSnapshot snap = calculator.snapshot(ctx.answers(), ctx.profile());
        String[] band = narrativeService.bandCopy(score.getTotal());
        return new LiteracyScoreResponse(
                score.getSessionId(),
                score.getTotal(),
                score.getCategoryBreakdown(),
                band[0], band[1],
                engine.describeUnknowns(ctx.answers()),
                snap,
                calculator.waterfall(snap),
                fromMap(score.getNarrativeJson()),
                score.getNarrativeDrafts().stream().map(DraftDto::of).toList(),
                score.getProviderUsed(),
                score.getModelUsed(),
                DISCLAIMER);
    }

    @SuppressWarnings("unchecked")
    static Narrative fromMap(Map<String, Object> map) {
        if (map == null) return null;
        return new Narrative(
                (String) map.get("summary"),
                (List<String>) map.get("strengths"),
                (List<String>) map.get("gaps"),
                (List<String>) map.get("nextSteps"));
    }
}
