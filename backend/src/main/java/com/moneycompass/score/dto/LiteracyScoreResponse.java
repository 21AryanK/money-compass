package com.moneycompass.score.dto;

import com.moneycompass.engine.FinancialSnapshot;
import com.moneycompass.engine.SnapshotCalculator.Waterfall;
import com.moneycompass.narrative.DraftDto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @param categoryBreakdown  a null value means "not assessed for this profile"
 * @param bandTitle          a plain-words reading of the total, e.g. "Solid foundation"
 * @param unknownNote        how "I don't know" answers were handled, or empty
 * @param snapshot           the user's own numbers the narrative was built from
 * @param waterfall          where each month's saving should go first; null without income
 * @param narrative          the current (latest) draft
 * @param drafts             every draft, oldest first, for the ‹ 2 / 3 › history
 * @param providerUsed       who wrote the current draft; {@code template} means no model answered
 */
public record LiteracyScoreResponse(
        UUID sessionId,
        int total,
        Map<String, Integer> categoryBreakdown,
        String bandTitle,
        String bandSub,
        String unknownNote,
        FinancialSnapshot snapshot,
        Waterfall waterfall,
        Narrative narrative,
        List<DraftDto> drafts,
        String providerUsed,
        String modelUsed,
        String disclaimer
) {}
