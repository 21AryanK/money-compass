package com.moneycompass.engine;

import java.util.List;
import java.util.Map;

/**
 * Evaluates a follow-up question's {@code ask_if} trigger. The vocabulary is
 * deliberately small — one entry per kind of predicate the question bank
 * actually needs — and is documented in {@code V3__question_bank_v2.sql}:
 *
 * <pre>
 * {"selectedIn":           {"question": q, "values": ["B","C"]}}
 * {"unknownOrNumberBelow": {"question": q, "value": 3}}
 * {"unknownOrScaleAtMost": {"question": q, "value": 2}}
 * {"knowledgeGap":         "COMPOUNDING"}
 * </pre>
 *
 * An unrecognised condition never matches, so a typo hides a follow-up
 * rather than showing it to everyone.
 */
final class AskIfCondition {

    private AskIfCondition() {
    }

    /**
     * @param effective answers with clarifiers already folded in
     * @param engine    for {@code knowledgeGap}, which looks at raw answers
     * @param raw       the answers exactly as given
     */
    @SuppressWarnings("unchecked")
    static boolean matches(Map<String, Object> condition, Map<String, Map<String, Object>> effective,
                           Map<String, Map<String, Object>> raw, AssessmentEngine engine) {
        if (condition == null) return true;

        if (condition.get("selectedIn") instanceof Map<?, ?> spec) {
            Map<String, Object> answer = effective.get((String) spec.get("question"));
            List<?> values = (List<?>) spec.get("values");
            String selected = Answers.selected(answer);
            return selected != null && values.stream().map(String::valueOf).anyMatch(selected::equals);
        }
        if (condition.get("unknownOrNumberBelow") instanceof Map<?, ?> spec) {
            Map<String, Object> answer = effective.get((String) spec.get("question"));
            if (answer == null) return false;
            if (Answers.isUnknown(answer)) return true;
            Double n = Answers.number(answer);
            return n != null && n < ((Number) spec.get("value")).doubleValue();
        }
        if (condition.get("unknownOrScaleAtMost") instanceof Map<?, ?> spec) {
            Map<String, Object> answer = effective.get((String) spec.get("question"));
            if (answer == null) return false;
            if (Answers.isUnknown(answer)) return true;
            Integer s = Answers.scale(answer);
            return s != null && s <= ((Number) spec.get("value")).intValue();
        }
        if (condition.get("knowledgeGap") instanceof String category) {
            return engine.knowledgeGap(raw, category);
        }
        return false;
    }
}
