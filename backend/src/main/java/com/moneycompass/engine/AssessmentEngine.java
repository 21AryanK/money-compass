package com.moneycompass.engine;

import com.moneycompass.domain.ProfileType;
import com.moneycompass.domain.Question;
import com.moneycompass.domain.QuestionCategory;
import com.moneycompass.score.ScoreResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The deterministic core of the questionnaire and the literacy score, as pure
 * functions of a session's answers (question code to answer value). No
 * database and no AI: everything that needs the answers loads them and asks
 * this class, which is what lets the unit tests exercise every rule directly.
 *
 * <h2>Adaptive flow</h2>
 * Every question accepts "I don't know" ({@code {"unknown": true}}). Three
 * kinds of question react to the answers so far, so a session grows or
 * shrinks as it goes:
 * <ul>
 *   <li><b>clarifies</b> — a simpler re-ask, served only when the question it
 *       clarifies got "I don't know". Its answer is translated through
 *       {@code value_map} into an answer for the original question (see
 *       {@link #effectiveAnswers}), so scoring and risk only ever see one.</li>
 *   <li><b>ask_if</b> — a follow-up, served only when its trigger holds
 *       against the answers so far (see {@link AskIfCondition}).</li>
 *   <li><b>advanced</b> — dropped once the user has said "I don't know" in
 *       that category without getting any of its knowledge questions right:
 *       depth is swapped for a basics probe rather than piled on.</li>
 * </ul>
 * The question cap applies only to base questions; follow-ups are extra.
 */
@Component
public class AssessmentEngine {

    private final QuestionBank bank;

    public AssessmentEngine(QuestionBank bank) {
        this.bank = bank;
    }

    public QuestionBank bank() {
        return bank;
    }

    // ------------------------------------------------------------------ flow

    /**
     * Answers with every clarified "I don't know" replaced by what its
     * clarifier's answer maps to, marked with {@code viaClarifier}.
     */
    public Map<String, Map<String, Object>> effectiveAnswers(Map<String, Map<String, Object>> raw) {
        Map<String, Map<String, Object>> eff = new LinkedHashMap<>(raw);
        for (Question q : bank.all()) {
            Map<String, Object> a = raw.get(q.getCode());
            if (q.getClarifies() == null || a == null || Answers.isUnknown(a)
                    || !Answers.isUnknown(raw.get(q.getClarifies()))) {
                continue;
            }
            Map<String, Object> mapped = q.getValueMap() == null ? null : q.getValueMap().get(Answers.selected(a));
            if (mapped != null) {
                Map<String, Object> value = new LinkedHashMap<>(mapped);
                value.put("viaClarifier", q.getCode());
                eff.put(q.getClarifies(), value);
            }
        }
        return eff;
    }

    /** Correct / wrong / unknown over the knowledge questions answered in a category. */
    public int[] knowledgeTally(Map<String, Map<String, Object>> raw, String category) {
        int correct = 0, wrong = 0, unknown = 0;
        for (Map.Entry<String, Map<String, Object>> e : raw.entrySet()) {
            Question q = bank.byCode(e.getKey());
            if (q == null || !q.getCategory().name().equals(category) || !q.isKnowledgeQuestion()) continue;
            if (Answers.isUnknown(e.getValue())) {
                unknown++;
                continue;
            }
            double[] pts = pointsFor(q, e.getValue());
            if (pts[0] >= pts[1]) correct++;
            else wrong++;
        }
        return new int[]{correct, wrong, unknown};
    }

    public boolean knowledgeGap(Map<String, Map<String, Object>> raw, String category) {
        int[] t = knowledgeTally(raw, category);
        return t[1] + t[2] > 0;
    }

    boolean isEligible(Question q, Map<String, Map<String, Object>> raw, Map<String, Map<String, Object>> eff) {
        if (q.getClarifies() != null) return Answers.isUnknown(raw.get(q.getClarifies()));
        if (q.getAskIf() != null && !AskIfCondition.matches(q.getAskIf(), eff, raw, this)) return false;
        if (q.isAdvanced()) {
            int[] t = knowledgeTally(raw, q.getCategory().name());
            if (t[2] > 0 && t[0] == 0) return false;
        }
        return true;
    }

    /** The questions still to come, in order, given the answers so far. */
    public List<Question> upcoming(Map<String, Map<String, Object>> raw, ProfileType profile, int maxBaseQuestions) {
        Map<String, Map<String, Object>> eff = effectiveAnswers(raw);
        long baseAnswered = raw.keySet().stream()
                .map(bank::byCode)
                .filter(q -> q != null && !q.isFollowUp())
                .count();
        List<Question> out = new ArrayList<>();
        for (Question q : bank.servedTo(profile)) {
            if (raw.containsKey(q.getCode()) || !isEligible(q, raw, eff)) continue;
            if (!q.isFollowUp()) {
                if (baseAnswered >= maxBaseQuestions) continue;
                baseAnswered++;
            }
            out.add(q);
        }
        return out;
    }

    /** Advanced questions currently being skipped because of "I don't know" answers. */
    public List<Question> skippedAdvanced(Map<String, Map<String, Object>> raw, ProfileType profile) {
        Map<String, Map<String, Object>> eff = effectiveAnswers(raw);
        return bank.servedTo(profile).stream()
                .filter(q -> q.isAdvanced() && !raw.containsKey(q.getCode()) && !isEligible(q, raw, eff))
                .toList();
    }

    /**
     * One sentence on how "I don't know" answers were handled, for the score
     * screen, or an empty string when there were none.
     */
    public String describeUnknowns(Map<String, Map<String, Object>> raw) {
        Map<String, Map<String, Object>> eff = effectiveAnswers(raw);
        int clarified = 0, toLearn = 0, assumed = 0;
        for (Map.Entry<String, Map<String, Object>> e : raw.entrySet()) {
            Question q = bank.byCode(e.getKey());
            if (q == null || !Answers.isUnknown(e.getValue()) || q.getClarifies() != null) continue;
            if (!Answers.isUnknown(eff.get(e.getKey()))) clarified++;
            else if (q.isKnowledgeQuestion()) toLearn++;
            else assumed++;
        }
        List<String> parts = new ArrayList<>();
        if (clarified > 0) parts.add(clarified + " answered through a simpler follow-up");
        if (toLearn > 0) parts.add(toLearn + " counted as topics to learn");
        if (assumed > 0) parts.add(assumed + " where we assumed the cautious answer");
        if (parts.isEmpty()) return "";
        int total = clarified + toLearn + assumed;
        return "You said “I don't know” to " + total + " question" + (total > 1 ? "s" : "") + ": "
                + String.join(", ", parts) + ".";
    }

    // ------------------------------------------------------------- scoring

    /**
     * @return {@code {earned, max}} for one answer. "I don't know" earns
     *         nothing but still counts toward the maximum.
     */
    @SuppressWarnings("unchecked")
    public double[] pointsFor(Question question, Map<String, Object> value) {
        Map<String, Object> rubric = question.getRubric();
        if (rubric == null) return new double[]{0, 0};
        Map<String, Object> v = Answers.isUnknown(value) ? Map.of() : value;

        return switch (question.getType()) {
            case SINGLE -> {
                String selected = Answers.selected(v);
                if (rubric.containsKey("correct")) {
                    double points = num(rubric.get("points"));
                    yield new double[]{String.valueOf(rubric.get("correct")).equals(selected) ? points : 0, points};
                }
                Map<String, Object> optionPoints = (Map<String, Object>) rubric.get("optionPoints");
                double max = optionPoints.values().stream().mapToDouble(AssessmentEngine::num).max().orElse(0);
                double earned = selected != null && optionPoints.containsKey(selected) ? num(optionPoints.get(selected)) : 0;
                yield new double[]{earned, max};
            }
            case MULTI -> {
                List<String> chosen = Answers.selectedList(v);
                List<String> correctSet = ((List<?>) rubric.get("correctSet")).stream().map(String::valueOf).toList();
                double points = num(rubric.get("points"));
                long correctChosen = chosen.stream().filter(correctSet::contains).count();
                long incorrectChosen = chosen.size() - correctChosen;
                // Partial credit, floored at zero: picking every option nets nothing.
                double fraction = Math.max(0, correctChosen - incorrectChosen) / (double) correctSet.size();
                yield new double[]{points * fraction, points};
            }
            case NUMBER -> {
                Double number = Answers.number(v);
                List<Map<String, Object>> thresholds = (List<Map<String, Object>>) rubric.get("numberThresholds");
                double max = thresholds.stream().mapToDouble(t -> num(t.get("points"))).max().orElse(0);
                double earned = number == null ? 0 : thresholds.stream()
                        .filter(t -> number >= num(t.get("min")))
                        .mapToDouble(t -> num(t.get("points"))).max().orElse(0);
                yield new double[]{earned, max};
            }
            case SCALE -> {
                Map<String, Object> scale = (Map<String, Object>) rubric.get("scale");
                double max = scale.values().stream().mapToDouble(AssessmentEngine::num).max().orElse(0);
                Integer s = Answers.scale(v);
                double earned = s != null && scale.containsKey(String.valueOf(s)) ? num(scale.get(String.valueOf(s))) : 0;
                yield new double[]{earned, max};
            }
        };
    }

    /**
     * Weighted total and per-category breakdown. A category with no scored
     * answers is {@code null} — "not assessed for this profile", which is
     * different from scoring zero on it.
     */
    public ScoreResult score(Map<String, Map<String, Object>> raw) {
        Map<String, double[]> buckets = buckets(effectiveAnswers(raw));
        Map<String, Integer> breakdown = new LinkedHashMap<>();
        double earned = 0, max = 0;
        for (QuestionCategory category : QuestionCategory.values()) {
            double[] b = buckets.get(category.name());
            breakdown.put(category.name(), b == null ? null : pct(b[0], b[1]));
            if (b != null) {
                earned += b[0];
                max += b[1];
            }
        }
        return new ScoreResult(pct(earned, max), breakdown);
    }

    /** One category's percentage, or a neutral 50 when nothing in it was scored. */
    public int categoryPercentage(Map<String, Map<String, Object>> raw, QuestionCategory category) {
        double[] b = buckets(effectiveAnswers(raw)).get(category.name());
        return b == null ? 50 : pct(b[0], b[1]);
    }

    private Map<String, double[]> buckets(Map<String, Map<String, Object>> effective) {
        Map<String, double[]> buckets = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : effective.entrySet()) {
            Question q = bank.byCode(e.getKey());
            // Informational questions (age, income, clarifiers) have no rubric and aren't scored.
            if (q == null || q.getRubric() == null) continue;
            double[] pts = pointsFor(q, e.getValue());
            double[] b = buckets.computeIfAbsent(q.getCategory().name(), c -> new double[2]);
            b[0] += pts[0] * q.getWeight();
            b[1] += pts[1] * q.getWeight();
        }
        return buckets;
    }

    static int pct(double earned, double max) {
        return max == 0 ? 0 : (int) Math.round(100 * earned / max);
    }

    private static double num(Object o) {
        return ((Number) o).doubleValue();
    }
}
