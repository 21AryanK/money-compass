package com.moneycompass.common;

import org.springframework.http.HttpStatus;

/**
 * Thrown by ResilientChatClient when both primary and fallback fail. Wired up
 * in Phase 3; declared here so the exception handler covers it from the start.
 */
public class AiUnavailableException extends ApiException {
    public AiUnavailableException(String detail) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "AI service unavailable", detail);
    }
}
