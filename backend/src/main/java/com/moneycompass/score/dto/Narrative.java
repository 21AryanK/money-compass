package com.moneycompass.score.dto;

import java.util.List;

/** The one AI-authored part of a literacy score. Generated via {@code ChatClient.entity(Narrative.class)}. */
public record Narrative(String summary, List<String> strengths, List<String> gaps, List<String> nextSteps) {}
