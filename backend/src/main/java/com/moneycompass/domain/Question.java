package com.moneycompass.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
}
