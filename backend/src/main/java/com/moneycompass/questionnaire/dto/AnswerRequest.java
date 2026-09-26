package com.moneycompass.questionnaire.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.Map;

/**
 * @param value shape depends on the question's type: {@code {"selected": "B"}},
 *              {@code {"selected": ["A","C"]}}, {@code {"number": 6}}, or
 *              {@code {"scale": 4}} — or {@code {"unknown": true}} for "I don't
 *              know about this", accepted on every question.
 *              {@link com.moneycompass.questionnaire.QuestionnaireService}
 *              validates the shape against the question actually being answered.
 */
public record AnswerRequest(
        @NotBlank String questionCode,
        @NotEmpty Map<String, Object> value
) {}
