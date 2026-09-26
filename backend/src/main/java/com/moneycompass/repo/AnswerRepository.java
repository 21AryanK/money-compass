package com.moneycompass.repo;

import com.moneycompass.domain.Answer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnswerRepository extends JpaRepository<Answer, Long> {

    List<Answer> findBySessionIdOrderByAnsweredAtAsc(UUID sessionId);

    /** Id breaks ties: answers saved in the same transaction share a timestamp. */
    List<Answer> findBySessionIdOrderByAnsweredAtAscIdAsc(UUID sessionId);

    Optional<Answer> findBySessionIdAndQuestionCode(UUID sessionId, String questionCode);

    long countBySessionId(UUID sessionId);
}
