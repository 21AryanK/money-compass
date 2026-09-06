package com.moneycompass.auth;

import com.moneycompass.auth.dto.*;
import com.moneycompass.common.ConflictException;
import com.moneycompass.common.UnauthorizedException;
import com.moneycompass.domain.User;
import com.moneycompass.repo.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public TokenResponse register(RegisterRequest request) {
        String email = normalise(request.email());

        if (users.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("An account with that email already exists");
        }

        User user = new User(
                UUID.randomUUID(),
                email,
                passwordEncoder.encode(request.password()),
                request.profileType());

        users.save(user);

        JwtService.TokenPair pair = jwtService.issue(user);
        return new TokenResponse(pair.token(), pair.expiresAt());
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        User user = users.findByEmailIgnoreCase(normalise(request.email()))
                .orElse(null);

        // Run the hash comparison even when the user does not exist, so the
        // response time does not reveal which emails are registered.
        boolean valid = user != null
                && passwordEncoder.matches(request.password(), user.getPasswordHash());

        if (!valid) {
            if (user == null) {
                passwordEncoder.matches(request.password(), DUMMY_HASH);
            }
            throw new UnauthorizedException("Invalid email or password");
        }

        JwtService.TokenPair pair = jwtService.issue(user);
        return new TokenResponse(pair.token(), pair.expiresAt());
    }

    private static String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** A valid BCrypt hash of a value nobody knows, used only for timing parity. */
    private static final String DUMMY_HASH =
            "$2a$12$C6UzMDM.H6dfI/f/IKcEe.4Uy3fJb6xVUqFHiOFvHkNJHNMkQPS6O";
}
