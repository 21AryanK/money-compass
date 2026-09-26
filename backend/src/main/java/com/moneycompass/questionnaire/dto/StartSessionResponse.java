package com.moneycompass.questionnaire.dto;

import java.util.UUID;

public record StartSessionResponse(UUID sessionId, QuestionDto firstQuestion, ProgressDto progress) {}
