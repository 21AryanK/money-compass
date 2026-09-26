package com.moneycompass.score;

import com.moneycompass.PostgresTestContainer;
import com.moneycompass.domain.*;
import com.moneycompass.repo.AnswerRepository;
import com.moneycompass.repo.AssessmentSessionRepository;
import com.moneycompass.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the deterministic scoring formula against the real seeded question
 * bank rather than hand-built fixtures, since the rubric shapes in
 * {@code V2__seed_questions.sql} are exactly what {@link LiteracyScoringService}
 * has to agree with. Real Postgres for the same reason as
 * {@link PostgresTestContainer}: JSONB rubric parsing is exactly the kind of
 * thing H2 would not catch a mismatch in.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
@TestPropertySource(properties = {
        "moneycompass.security.jwt-secret=test-secret-key-for-integration-tests-only-32b",
        "spring.config.import="
})
class LiteracyScoringServiceTest {

    @Autowired
    private LiteracyScoringService scoringService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AssessmentSessionRepository sessionRepository;

    @Autowired
    private AnswerRepository answerRepository;

    @Test
    void computesAWeightedTotalAndPerCategoryBreakdownFromRubricsAlone() {
        UUID sessionId = newSession();

        // COMPOUNDING, weight 2, correct -> full credit: earned 10*2, max 10*2.
        answerRepository.save(new Answer(sessionId, "compounding_rule_of_72", Map.of("selected", "B")));
        // BUDGETING, weight 1, wrong -> zero credit: earned 0, max 10*1.
        answerRepository.save(new Answer(sessionId, "budgeting_needs_wants", Map.of("selected", "A")));
        // SAVING, weight 2: 4 months clears the 3-month (6pt) threshold but not
        // the 6-month (10pt) one -> earned 6*2, max 10*2.
        answerRepository.save(new Answer(sessionId, "saving_emergency_fund_months", Map.of("number", 4)));

        ScoreResult result = scoringService.score(sessionId);

        // earned = 20 + 0 + 12 = 32; max = 20 + 10 + 20 = 50 -> round(100*32/50) = 64.
        assertThat(result.total()).isEqualTo(64);
        assertThat(result.categoryBreakdown())
                .containsEntry("COMPOUNDING", 100)
                .containsEntry("BUDGETING", 0)
                .containsEntry("SAVING", 60)
                .containsEntry("RISK", null)
                .containsEntry("RETIREMENT", null);
    }

    @Test
    void categoryPercentageDefaultsToANeutralFiftyWhenNothingInThatCategoryIsAnswered() {
        UUID sessionId = newSession();
        answerRepository.save(new Answer(sessionId, "compounding_rule_of_72", Map.of("selected", "B")));

        assertThat(scoringService.categoryPercentage(sessionId, QuestionCategory.RISK)).isEqualTo(50);
        assertThat(scoringService.categoryPercentage(sessionId, QuestionCategory.COMPOUNDING)).isEqualTo(100);
    }

    private UUID newSession() {
        User user = new User(
                UUID.randomUUID(),
                "scoring-" + UUID.randomUUID() + "@example.com",
                // A valid BCrypt hash of a value nobody knows; never logged in with.
                "$2a$12$C6UzMDM.H6dfI/f/IKcEe.4Uy3fJb6xVUqFHiOFvHkNJHNMkQPS6O",
                ProfileType.PROFESSIONAL);
        userRepository.save(user);

        AssessmentSession session = new AssessmentSession(UUID.randomUUID(), user.getId(), user.getProfileType());
        sessionRepository.save(session);
        return session.getId();
    }
}
