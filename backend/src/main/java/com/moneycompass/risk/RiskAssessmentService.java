package com.moneycompass.risk;

import com.moneycompass.domain.ProfileType;
import com.moneycompass.engine.SessionAnswers;
import com.moneycompass.engine.SnapshotCalculator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Deterministic tolerance, capacity and allocation for a stored session.
 *
 * <p>Tolerance is "how this session scored on the RISK category alone".
 * Capacity is a separate, itemised calculation — a profile baseline adjusted
 * by age, emergency fund, debt, horizon, savings rate, EMIs, dependents and
 * insurance (see {@link SnapshotCalculator#capacityFactors}) — because
 * tolerance ("how would you feel") and capacity ("can you actually afford
 * to") are different questions. Capacity only ever caps tolerance, never
 * raises it: {@code band = min(toleranceBand, capacityBand)}.
 */
@Service
public class RiskAssessmentService {

    private final SessionAnswers sessionAnswers;
    private final SnapshotCalculator calculator;

    public RiskAssessmentService(SessionAnswers sessionAnswers, SnapshotCalculator calculator) {
        this.sessionAnswers = sessionAnswers;
        this.calculator = calculator;
    }

    @Transactional(readOnly = true)
    public RiskResult assess(UUID sessionId, ProfileType profileType) {
        SnapshotCalculator.RiskAssessment r = calculator.assessRisk(sessionAnswers.of(sessionId), profileType);
        return new RiskResult(r.tolerance(), r.capacity(), r.band(), r.allocation(), r.factors());
    }
}
