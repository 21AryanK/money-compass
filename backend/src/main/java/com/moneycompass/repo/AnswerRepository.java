package com.moneycompass.repo;

import com.moneycompass.domain.Answer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnswerRepository extends JpaRepository<Answer, Long> {

    List<Answer> findBySessionIdOrderByAnsweredAtAsc(UUID sessionId);

    Optional<Answer> findBySessionIdAndQuestionCode(UUID sessionId, String questionCode);

    long countBySessionId(UUID sessionId);
}
