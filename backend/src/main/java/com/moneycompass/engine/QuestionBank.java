package com.moneycompass.engine;

import com.moneycompass.domain.ProfileType;
import com.moneycompass.domain.Question;
import com.moneycompass.repo.QuestionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The seeded question bank, loaded once. Questions are only ever written by
 * Flyway, so there is nothing to invalidate at runtime; holding them in
 * memory keeps the adaptive engine, which re-derives "what's next" from all
 * answers on every request, from re-querying the table each time.
 *
 * <p>{@link #byCode} includes retired (inactive) questions so answers given
 * to them in older sessions still score; {@link #servedTo} only ever returns
 * active ones.
 */
@Component
public class QuestionBank {

    private final Supplier<List<Question>> loader;
    private volatile Map<String, Question> byCode;
    private volatile List<Question> ordered;

    @Autowired
    public QuestionBank(QuestionRepository repository) {
        this.loader = repository::findAll;
    }

    /** For unit tests: a fixed bank, no database. */
    public static QuestionBank of(List<Question> questions) {
        return new QuestionBank(() -> questions);
    }

    private QuestionBank(Supplier<List<Question>> loader) {
        this.loader = loader;
    }

    public Question byCode(String code) {
        return index().get(code);
    }

    /** Active questions for a profile, in the order they're served. */
    public List<Question> servedTo(ProfileType profileType) {
        return all().stream().filter(Question::isActive).filter(q -> q.appliesTo(profileType)).toList();
    }

    /** Every question, active or not, in serving order. */
    public List<Question> all() {
        if (ordered == null) {
            synchronized (this) {
                if (ordered == null) {
                    List<Question> loaded = loader.get().stream()
                            .sorted(Comparator.comparingInt(Question::getSortOrder).thenComparing(Question::getCode))
                            .toList();
                    Map<String, Question> index = new LinkedHashMap<>();
                    loaded.forEach(q -> index.put(q.getCode(), q));
                    byCode = index;
                    ordered = loaded;
                }
            }
        }
        return ordered;
    }

    private Map<String, Question> index() {
        all();
        return byCode;
    }
}
