package com.moneycompass.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * One run through the questionnaire.
 *
 * <p>Named AssessmentSession rather than Session because {@code jakarta.servlet}
 * and Hibernate both export a {@code Session} type and the collision is a
 * constant source of wrong imports. The table is still {@code sessions}.
 */
@Entity
@Table(name = "sessions")
public class AssessmentSession {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SessionStatus status;

    @Column(name = "started_at", nullable = false, insertable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected AssessmentSession() {
        // for JPA
    }

    public AssessmentSession(UUID id, UUID userId) {
        this.id = id;
        this.userId = userId;
        this.status = SessionStatus.IN_PROGRESS;
    }

    public void complete(Instant at) {
        this.status = SessionStatus.COMPLETED;
        this.completedAt = at;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public SessionStatus getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
