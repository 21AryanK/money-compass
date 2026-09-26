package com.moneycompass.engine;

import com.moneycompass.domain.Answer;
import com.moneycompass.repo.AnswerRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Loads a session's answers in the shape the engine works on: question code to value, in answer order. */
@Component
public class SessionAnswers {

    private final AnswerRepository answerRepository;

    public SessionAnswers(AnswerRepository answerRepository) {
        this.answerRepository = answerRepository;
    }

    @Transactional(readOnly = true)
    public Map<String, Map<String, Object>> of(UUID sessionId) {
        Map<String, Map<String, Object>> raw = new LinkedHashMap<>();
        for (Answer a : answerRepository.findBySessionIdOrderByAnsweredAtAscIdAsc(sessionId)) {
            raw.put(a.getQuestionCode(), a.getValue());
        }
        return raw;
    }
}
