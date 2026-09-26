package com.moneycompass.engine;

import com.moneycompass.common.NotFoundException;
import com.moneycompass.domain.AssessmentSession;
import com.moneycompass.domain.ProfileType;
import com.moneycompass.domain.User;
import com.moneycompass.repo.AssessmentSessionRepository;
import com.moneycompass.repo.UserRepository;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Loads what every results endpoint starts from — the session (checked to
 * belong to the caller), the caller's profile, and the answers — in one
 * place, so the ownership rule can't be forgotten in one of them.
 */
@Component
public class SessionContext {

    private final AssessmentSessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final SessionAnswers sessionAnswers;

    public SessionContext(AssessmentSessionRepository sessionRepository, UserRepository userRepository,
                          SessionAnswers sessionAnswers) {
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.sessionAnswers = sessionAnswers;
    }

    public record Loaded(AssessmentSession session, User user, Map<String, Map<String, Object>> answers) {
        /** The profile the assessment was taken as, which may differ from the user's current one. */
        public ProfileType profile() {
            return session.getProfileType() != null ? session.getProfileType() : user.getProfileType();
        }
    }

    public Loaded load(UUID sessionId, UUID userId) {
        AssessmentSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("Session not found"));
        if (!session.getUserId().equals(userId)) {
            // 404, not 403: a caller who does not own this session should not
            // be able to tell the id exists at all.
            throw new NotFoundException("Session not found");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User no longer exists"));
        return new Loaded(session, user, sessionAnswers.of(sessionId));
    }
}
