package com.moneycompass.score;

import com.moneycompass.domain.QuestionCategory;
import com.moneycompass.engine.AssessmentEngine;
import com.moneycompass.engine.SessionAnswers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The deterministic literacy score for a stored session: a 0-100 total and a
 * per-category breakdown, from the questions' rubrics alone. No AI model is
 * involved, which is what makes the score stable across refreshes and
 * testable without one.
 *
 * <p>The rules themselves — "I don't know" answers, clarifiers folded back
 * onto the question they clarify, unassessed categories reported as
 * {@code null} — live in {@link AssessmentEngine}; this class just loads the
 * answers for it.
 */
@Service
public class LiteracyScoringService {

    private final SessionAnswers sessionAnswers;
    private final AssessmentEngine engine;

    public LiteracyScoringService(SessionAnswers sessionAnswers, AssessmentEngine engine) {
        this.sessionAnswers = sessionAnswers;
        this.engine = engine;
    }

    @Transactional(readOnly = true)
    public ScoreResult score(UUID sessionId) {
        return engine.score(sessionAnswers.of(sessionId));
    }

    /**
     * The percentage score for one category alone. Used as the risk
     * tolerance score, taking the RISK category on its own.
     *
     * @return 50 - a neutral midpoint, not "half correct" - if the session
     *         has not answered any scored question in that category yet
     */
    @Transactional(readOnly = true)
    public int categoryPercentage(UUID sessionId, QuestionCategory category) {
        return engine.categoryPercentage(sessionAnswers.of(sessionId), category);
    }
}
