package com.moneycompass.score;

import com.moneycompass.score.dto.LiteracyScoreResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Both endpoints share one implementation: compute-if-absent, then return
 * what is stored. POST and GET differ only in HTTP semantics, not behaviour -
 * a GET is safe to call speculatively without triggering a fresh AI call for
 * an already-scored session.
 */
@RestController
@RequestMapping("/api/score")
public class ScoreController {

    private final ScoreService scoreService;

    public ScoreController(ScoreService scoreService) {
        this.scoreService = scoreService;
    }

    @PostMapping("/{sessionId}")
    public LiteracyScoreResponse compute(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        return scoreService.computeOrFetch(sessionId, userId(jwt));
    }

    @GetMapping("/{sessionId}")
    public LiteracyScoreResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        return scoreService.computeOrFetch(sessionId, userId(jwt));
    }

    /** A new draft of the explanation that takes a different angle; earlier drafts are kept. */
    @PostMapping("/{sessionId}/regenerate")
    public LiteracyScoreResponse regenerate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        return scoreService.regenerate(sessionId, userId(jwt));
    }

    private UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
