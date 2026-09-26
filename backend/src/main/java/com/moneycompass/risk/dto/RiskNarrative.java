package com.moneycompass.risk.dto;

import java.util.List;

public record RiskNarrative(String rationale, List<String> considerations, List<String> avoid) {}
