package com.moneycompass.auth;

import com.moneycompass.config.MoneyCompassProperties;
import com.moneycompass.domain.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Mints access tokens. Verification is handled by the {@code JwtDecoder} bean
 * in SecurityConfig, so there is deliberately no verify method here.
 */
@Service
public class JwtService {

    private static final String ISSUER = "money-compass";

    private final JwtEncoder encoder;
    private final Duration ttl;

    public JwtService(JwtEncoder encoder, MoneyCompassProperties properties) {
        this.encoder = encoder;
        this.ttl = Duration.ofHours(properties.security().jwtTtlHours());
    }

    public TokenPair issue(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(ttl);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(expiry)
                // Subject is the user id, not the email. Emails can change;
                // the id is the thing every downstream query joins on.
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("profileType", user.getProfileType().name())
                .build();

        // The header must name HS256: without one the encoder defaults to
        // RS256, finds no RSA key among the configured JWKs, and fails with
        // "Failed to select a JWK signing key".
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new TokenPair(token, expiry);
    }

    public record TokenPair(String token, Instant expiresAt) {}
}
