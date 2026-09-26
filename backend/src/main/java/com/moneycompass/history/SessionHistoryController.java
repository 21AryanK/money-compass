package com.moneycompass.history;

import com.moneycompass.domain.*;
import com.moneycompass.repo.AnswerRepository;
import com.moneycompass.repo.AssessmentSessionRepository;
import com.moneycompass.repo.LiteracyScoreRepository;
import com.moneycompass.repo.RiskProfileRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The caller's assessments, newest first, for the dashboard: the latest score
 * and band, the change since the previous one, and an unfinished session to
 * resume.
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionHistoryController {

    private final AssessmentSessionRepository sessionRepository;
    private final AnswerRepository answerRepository;
    private final LiteracyScoreRepository scoreRepository;
    private final RiskProfileRepository riskRepository;

    public SessionHistoryController(AssessmentSessionRepository sessionRepository, AnswerRepository answerRepository,
                                    LiteracyScoreRepository scoreRepository, RiskProfileRepository riskRepository) {
        this.sessionRepository = sessionRepository;
        this.answerRepository = answerRepository;
        this.scoreRepository = scoreRepository;
        this.riskRepository = riskRepository;
    }

    /**
     * @param total   null until the session has been scored
     * @param band    the band being planned around, or null until risk is assessed
     */
    public record SessionSummary(UUID sessionId, SessionStatus status, ProfileType profileType, Instant startedAt,
                                 Instant completedAt, long answered, Integer total, RiskBand band) {}

    @GetMapping
    @Transactional(readOnly = true)
    public List<SessionSummary> list(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return sessionRepository.findByUserIdOrderByStartedAtDesc(userId).stream()
                .map(s -> new SessionSummary(
                        s.getId(), s.getStatus(), s.getProfileType(), s.getStartedAt(), s.getCompletedAt(),
                        answerRepository.countBySessionId(s.getId()),
                        scoreRepository.findBySessionId(s.getId()).map(LiteracyScore::getTotal).orElse(null),
                        riskRepository.findBySessionId(s.getId()).map(RiskProfile::getActiveBand).orElse(null)))
                .toList();
    }
}
