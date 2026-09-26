package com.moneycompass.questionnaire.dto;

import java.util.UUID;

public record CompleteResponse(UUID sessionId, String status) {}
