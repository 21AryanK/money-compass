package com.moneycompass.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;

/**
 * A single questionnaire item. Seeded by Flyway in Phase 2, never created at
 * runtime, so there is no public constructor beyond the JPA one.
 *
 * <p>{@code options} and {@code rubric} are JSONB. Their shape depends on
 * {@link #type}:
 * <ul>
 *   <li>SINGLE/MULTI options: {@code {"A": "label", "B": "label"}}</li>
 *   <li>knowledge rubric: {@code {"correct": "B", "points": 10}}</li>
 *   <li>scale rubric: {@code {"scale": {"1":0,"2":3,"3":6,"4":8,"5":10}}}</li>
 * </ul>
 */
@Entity
@Table(name = "questions")
public class Question {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private QuestionType type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> options;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private QuestionCategory category;

    /**
     * Postgres {@code text[]}, holding {@link ProfileType} names. Mapped as a
     * native array rather than JSONB so the engine can filter with the
     * {@code = ANY} operator in SQL instead of loading every row.
     */
    @Column(name = "applicable_profiles", nullable = false, columnDefinition = "text[]")
    private String[] applicableProfiles;

    /** Relative contribution of this question's category to the total score. */
    @Column(nullable = false)
    private int weight = 1;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> rubric;

    // ---- Added in V3: presentation and the adaptive flow. See V3__question_bank_v2.sql.

    @Column(columnDefinition = "text")
    private String hint;

    @Column(length = 64)
    private String unit;

    @Column(name = "min_value", columnDefinition = "numeric")
    private Double minValue;

    @Column(name = "max_value", columnDefinition = "numeric")
    private Double maxValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scale_labels", columnDefinition = "jsonb")
    private List<String> scaleLabels;

    /** Per-profile overrides of text, hint, unit and options, keyed by {@link ProfileType} name. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Map<String, Object>> variants;

    /** When set, this is a simpler re-ask of that question, served only after it got "I don't know". */
    @Column(length = 64)
    private String clarifies;

    /** For a clarifier: option code to the answer value it stands for on the clarified question. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "value_map", columnDefinition = "jsonb")
    private Map<String, Map<String, Object>> valueMap;

    /** A follow-up's trigger. See {@code AskIfCondition}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ask_if", columnDefinition = "jsonb")
    private Map<String, Object> askIf;

    @Column(name = "follow_up_reason", columnDefinition = "text")
    private String followUpReason;

    @Column(nullable = false)
    private boolean advanced;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "knowledge_topic", columnDefinition = "text")
    private String knowledgeTopic;

    @Column(name = "wiki_title", columnDefinition = "text")
    private String wikiTitle;

    @Column(nullable = false)
    private boolean active = true;

    protected Question() {
        // for JPA
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getText() { return text; }
    public QuestionType getType() { return type; }
    public Map<String, Object> getOptions() { return options; }
    public QuestionCategory getCategory() { return category; }
    public String[] getApplicableProfiles() { return applicableProfiles; }
    public int getWeight() { return weight; }
    public Map<String, Object> getRubric() { return rubric; }
    public String getHint() { return hint; }
    public String getUnit() { return unit; }
    public Double getMinValue() { return minValue; }
    public Double getMaxValue() { return maxValue; }
    public List<String> getScaleLabels() { return scaleLabels; }
    public Map<String, Map<String, Object>> getVariants() { return variants; }
    public String getClarifies() { return clarifies; }
    public Map<String, Map<String, Object>> getValueMap() { return valueMap; }
    public Map<String, Object> getAskIf() { return askIf; }
    public String getFollowUpReason() { return followUpReason; }
    public boolean isAdvanced() { return advanced; }
    public int getSortOrder() { return sortOrder; }
    public String getKnowledgeTopic() { return knowledgeTopic; }
    public String getWikiTitle() { return wikiTitle; }
    public boolean isActive() { return active; }

    /** A follow-up is only served in reaction to earlier answers, and doesn't count toward the question cap. */
    public boolean isFollowUp() { return clarifies != null || askIf != null; }

    /** A question with a right answer, as opposed to a behavioural or informational one. */
    public boolean isKnowledgeQuestion() {
        return rubric != null && (rubric.containsKey("correct") || rubric.containsKey("correctSet"));
    }

    public boolean appliesTo(ProfileType profileType) {
        return java.util.Arrays.asList(applicableProfiles).contains(profileType.name());
    }

    /**
     * Test-only factory: the entity is otherwise only ever loaded from the
     * seeded table, so this is how engine unit tests build a question bank.
     */
    public static Question forTest(String code, QuestionType type, QuestionCategory category, int weight,
                                   Map<String, Object> options, Map<String, Object> rubric, String... profiles) {
        Question q = new Question();
        q.code = code;
        q.text = code;
        q.type = type;
        q.category = category;
        q.weight = weight;
        q.options = options;
        q.rubric = rubric;
        q.applicableProfiles = profiles.length == 0
                ? new String[]{"STUDENT", "PROFESSIONAL", "RETIREE"} : profiles;
        return q;
    }

    /** Test-only: fluent setters for the adaptive-flow fields. */
    public Question withClarifies(String clarifies, Map<String, Map<String, Object>> valueMap) {
        this.clarifies = clarifies;
        this.valueMap = valueMap;
        return this;
    }

    public Question withAskIf(Map<String, Object> askIf) {
        this.askIf = askIf;
        return this;
    }

    public Question withAdvanced(boolean advanced) {
        this.advanced = advanced;
        return this;
    }

    public Question withSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
        return this;
    }
}
