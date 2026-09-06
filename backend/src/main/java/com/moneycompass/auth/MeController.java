package com.moneycompass.auth;

import com.moneycompass.common.NotFoundException;
import com.moneycompass.domain.ProfileType;
import com.moneycompass.domain.User;
import com.moneycompass.repo.UserRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The authenticated caller's own record. Exists mainly so there is a protected
 * endpoint to prove the JWT filter chain works end to end, but the frontend
 * uses it too, to restore state after a page reload.
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final UserRepository users;

    public MeController(UserRepository users) {
        this.users = users;
    }

    @GetMapping
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        User user = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("User no longer exists"));
        return new MeResponse(user.getId(), user.getEmail(), user.getProfileType());
    }

    public record MeResponse(UUID id, String email, ProfileType profileType) {}
}
