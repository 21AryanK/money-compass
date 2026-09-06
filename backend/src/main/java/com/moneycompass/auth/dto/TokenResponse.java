package com.moneycompass.auth.dto;

import java.time.Instant;

/**
 * @param token     signed JWT, sent back as {@code Authorization: Bearer <token>}
 * @param expiresAt when the token stops being accepted
 */
public record TokenResponse(String token, Instant expiresAt) {}
