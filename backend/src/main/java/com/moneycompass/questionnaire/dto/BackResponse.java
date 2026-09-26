package com.moneycompass.questionnaire.dto;

import java.util.Map;

/**
 * The result of stepping back one question: the removed answer's question,
 * served again, with what was answered so the client can pre-fill it.
 */
public record BackResponse(QuestionDto question, Map<String, Object> previousValue, ProgressDto progress) {}
