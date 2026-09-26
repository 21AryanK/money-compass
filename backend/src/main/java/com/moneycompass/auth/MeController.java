package com.moneycompass.auth;

import com.moneycompass.common.NotFoundException;
import com.moneycompass.domain.ProfileType;
import com.moneycompass.domain.User;
import com.moneycompass.repo.UserRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

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

    /**
     * Switches the profile the next assessment is taken as. Earlier sessions
     * keep the profile they were answered for, so their results don't change.
     */
    @PutMapping("/profile")
    @Transactional
    public MeResponse changeProfile(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProfileRequest request) {
        UUID userId = UUID.fromString(jwt.getSubject());
        User user = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("User no longer exists"));
        user.setProfileType(request.profileType());
        users.save(user);
        return new MeResponse(user.getId(), user.getEmail(), user.getProfileType());
    }

    public record ProfileRequest(@NotNull ProfileType profileType) {}

    public record MeResponse(UUID id, String email, ProfileType profileType) {}
}
