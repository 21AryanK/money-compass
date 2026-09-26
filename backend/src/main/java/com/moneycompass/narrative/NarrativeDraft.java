package com.moneycompass.narrative;

import java.util.List;
import java.util.Map;

/**
 * One draft of an AI-written card, as stored in the {@code narrative_drafts}
 * JSONB columns and returned to the client for the ‹ 2 / 3 › draft history.
 *
 * @param content   the narrative itself: summary/strengths/gaps/nextSteps for
 *                  the score, rationale/considerations/avoid for risk
 * @param provider  who wrote it: {@code ollama}, {@code openai}, {@code bedrock},
 *                  or {@code template} when no model was reachable
 * @param pointIds  which grounded points it drew on, so the next draft can
 *                  avoid repeating them
 * @param createdAt ISO-8601 instant; a string so the JSONB mapping needs no
 *                  java.time support
 * @param angle     which framing its summary used
 */
public record NarrativeDraft(
        Map<String, Object> content,
        String provider,
        String model,
        long latencyMs,
        Integer tokens,
        String createdAt,
        List<String> pointIds,
        String angle
) {}
