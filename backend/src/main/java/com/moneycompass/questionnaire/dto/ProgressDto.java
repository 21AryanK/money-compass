package com.moneycompass.questionnaire.dto;

/**
 * @param estimatedTotal answered plus still to come; it moves as follow-ups
 *                       are triggered or advanced questions are skipped
 * @param skippedAdvanced advanced questions currently skipped because of
 *                        "I don't know" answers — the client compares it with
 *                        the previous value to say "skipped N advanced questions"
 */
public record ProgressDto(int answered, int estimatedTotal, int skippedAdvanced) {}
