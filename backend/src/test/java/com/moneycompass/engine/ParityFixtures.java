package com.moneycompass.engine;

import com.moneycompass.domain.Question;
import com.moneycompass.domain.QuestionCategory;
import com.moneycompass.domain.QuestionType;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/**
 * Loads the fixtures in {@code src/test/resources/parity/}, generated from
 * {@code prototype/index.html} by running the prototype's own engine:
 *
 * <ul>
 *   <li>{@code question-bank.json} — the question bank in exactly the shape
 *       {@code V3__question_bank_v2.sql} stores it;</li>
 *   <li>{@code prototype-sessions.json} — randomised sessions (every profile,
 *       with and without "I don't know" answers) and what the prototype
 *       computed for each: questions served, score, risk, snapshot, plan.</li>
 * </ul>
 */
public final class ParityFixtures {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ParityFixtures() {
    }

    public static QuestionBank bank() {
        List<Map<String, Object>> rows = read("/parity/question-bank.json");
        return QuestionBank.of(rows.stream().map(ParityFixtures::question).toList());
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> sessions() {
        return read("/parity/prototype-sessions.json");
    }

    @SuppressWarnings("unchecked")
    private static <T> T read(String path) {
        try (InputStream in = ParityFixtures.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing fixture " + path);
            return (T) JSON.readValue(in, Object.class);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The entity has no public constructor (it's only ever loaded from the table), so fields are set directly. */
    @SuppressWarnings("unchecked")
    private static Question question(Map<String, Object> row) {
        try {
            var ctor = Question.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            Question q = ctor.newInstance();
            set(q, "code", row.get("code"));
            set(q, "text", row.get("text"));
            set(q, "type", QuestionType.valueOf((String) row.get("type")));
            set(q, "options", row.get("options"));
            set(q, "category", QuestionCategory.valueOf((String) row.get("category")));
            set(q, "applicableProfiles", ((List<String>) row.get("applicableProfiles")).toArray(String[]::new));
            set(q, "weight", ((Number) row.get("weight")).intValue());
            set(q, "rubric", row.get("rubric"));
            set(q, "hint", row.get("hint"));
            set(q, "unit", row.get("unit"));
            set(q, "minValue", row.get("minValue") == null ? null : ((Number) row.get("minValue")).doubleValue());
            set(q, "maxValue", row.get("maxValue") == null ? null : ((Number) row.get("maxValue")).doubleValue());
            set(q, "scaleLabels", row.get("scaleLabels"));
            set(q, "variants", row.get("variants"));
            set(q, "clarifies", row.get("clarifies"));
            set(q, "valueMap", row.get("valueMap"));
            set(q, "askIf", row.get("askIf"));
            set(q, "followUpReason", row.get("followUpReason"));
            set(q, "advanced", row.get("advanced"));
            set(q, "sortOrder", ((Number) row.get("sortOrder")).intValue());
            set(q, "knowledgeTopic", row.get("knowledgeTopic"));
            set(q, "wikiTitle", row.get("wikiTitle"));
            set(q, "active", true);
            return q;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Object target, String field, Object value) throws ReflectiveOperationException {
        Field f = Question.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
