package com.moneycompass.feedback;

import com.moneycompass.engine.SessionContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Thumbs up / down on one draft of an AI-written card. Stored so the prompts
 * and templates can be judged against what people actually found useful.
 * Plain JDBC: it's one upsert and one delete, with nothing to map.
 */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final JdbcClient jdbc;
    private final SessionContext sessionContext;

    public FeedbackController(JdbcClient jdbc, SessionContext sessionContext) {
        this.jdbc = jdbc;
        this.sessionContext = sessionContext;
    }

    /**
     * @param target     SCORE or RISK
     * @param band       for RISK, the band the rationale was written for
     * @param draftIndex 0-based position in the draft history
     * @param rating     UP or DOWN, or null to clear an earlier rating
     */
    public record FeedbackRequest(@NotNull UUID sessionId,
                                  @NotNull @Pattern(regexp = "SCORE|RISK") String target,
                                  String band,
                                  @Min(0) int draftIndex,
                                  @Pattern(regexp = "UP|DOWN") String rating) {}

    @PostMapping
    @Transactional
    public ResponseEntity<Void> rate(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody FeedbackRequest request) {
        sessionContext.load(request.sessionId(), UUID.fromString(jwt.getSubject())); // ownership check
        String band = "RISK".equals(request.target()) && request.band() != null ? request.band() : "";
        if (request.rating() == null) {
            jdbc.sql("DELETE FROM narrative_feedback WHERE session_id = ? AND target = ? AND band = ? AND draft_index = ?")
                    .params(request.sessionId(), request.target(), band, request.draftIndex())
                    .update();
        } else {
            jdbc.sql("""
                            INSERT INTO narrative_feedback (session_id, target, band, draft_index, rating)
                            VALUES (?, ?, ?, ?, ?)
                            ON CONFLICT (session_id, target, band, draft_index)
                            DO UPDATE SET rating = EXCLUDED.rating, created_at = now()
                            """)
                    .params(request.sessionId(), request.target(), band, request.draftIndex(), request.rating())
                    .update();
        }
        return ResponseEntity.noContent().build();
    }
}
