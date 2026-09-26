package com.moneycompass.narrative;

import java.util.Map;

/** One draft as the client sees it — the stored draft without the bookkeeping used for regeneration. */
public record DraftDto(Map<String, Object> content, String provider, String model, long latencyMs, Integer tokens,
                       String createdAt) {

    public static DraftDto of(NarrativeDraft d) {
        return new DraftDto(d.content(), d.provider(), d.model(), d.latencyMs(), d.tokens(), d.createdAt());
    }
}
