package com.moneycompass.repo;

import com.moneycompass.domain.AssessmentSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AssessmentSessionRepository extends JpaRepository<AssessmentSession, UUID> {

    List<AssessmentSession> findByUserIdOrderByStartedAtDesc(UUID userId);
}
