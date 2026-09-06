package com.moneycompass.repo;

import com.moneycompass.domain.RiskProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RiskProfileRepository extends JpaRepository<RiskProfile, Long> {

    Optional<RiskProfile> findBySessionId(UUID sessionId);
}
