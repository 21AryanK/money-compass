package com.moneycompass.engine;

import java.util.List;
import java.util.Map;

/**
 * Reads the JSONB answer shapes: {@code {"selected":"B"}},
 * {@code {"selected":["A","C"]}}, {@code {"number":6}}, {@code {"scale":4}},
 * or {@code {"unknown":true}} for "I don't know about this".
 *
 * <p>A clarifier's answer, translated back onto the question it clarifies,
 * also carries {@code "viaClarifier": "<code>"} (see
 * {@link AssessmentEngine#effectiveAnswers}).
 */
public final class Answers {

    public static final String UNKNOWN = "unknown";

    private Answers() {
    }

    public static boolean isUnknown(Map<String, Object> value) {
        return value != null && Boolean.TRUE.equals(value.get(UNKNOWN));
    }

    /** The number answered, or null if not a number answer (or "I don't know"). */
    public static Double number(Map<String, Object> value) {
        if (value == null || isUnknown(value)) return null;
        return value.get("number") instanceof Number n ? n.doubleValue() : null;
    }

    /** The single option code chosen, or null. */
    public static String selected(Map<String, Object> value) {
        if (value == null || isUnknown(value)) return null;
        Object s = value.get("selected");
        return s instanceof String str ? str : null;
    }

    /** The option codes chosen on a MULTI question; empty if none. */
    public static List<String> selectedList(Map<String, Object> value) {
        if (value == null || isUnknown(value)) return List.of();
        return value.get("selected") instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    public static Integer scale(Map<String, Object> value) {
        if (value == null || isUnknown(value)) return null;
        return value.get("scale") instanceof Number n ? n.intValue() : null;
    }
}
