package com.moneycompass.repo;

import com.moneycompass.domain.LiteracyScore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LiteracyScoreRepository extends JpaRepository<LiteracyScore, Long> {

    Optional<LiteracyScore> findBySessionId(UUID sessionId);
}
