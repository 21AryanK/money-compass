package com.moneycompass.questionnaire.dto;

import com.moneycompass.domain.QuestionType;

import java.util.List;
import java.util.Map;

/**
 * A question as this user's profile should see it: variant wording already
 * merged over the base.
 *
 * @param options             present for SINGLE and MULTI only
 * @param followUpNote        why the flow just grew — "a simpler way to ask the
 *                            last question", or a follow-up's reason — or null
 * @param unknownConsequence  what answering "I don't know" will do here
 * @param wikiTitle           a Wikipedia article to read up on the topic, or null
 * @param last                true when answering (other than with "I don't
 *                            know") would end the questionnaire, so the client
 *                            can label its button "Finish"
 */
public record QuestionDto(
        String code,
        String text,
        QuestionType type,
        Map<String, String> options,
        String category,
        String hint,
        String unit,
        Double min,
        Double max,
        List<String> scaleLabels,
        String followUpNote,
        String unknownConsequence,
        String wikiTitle,
        String wikiUrl,
        boolean last
) {}
