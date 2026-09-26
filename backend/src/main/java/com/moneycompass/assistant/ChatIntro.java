package com.moneycompass.assistant;

import java.util.List;

/** The assistant's opening message and suggested first questions. */
public record ChatIntro(String greeting, List<String> suggestions, String provider, String model) {}
