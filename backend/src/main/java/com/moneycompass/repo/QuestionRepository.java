package com.moneycompass.repo;

import com.moneycompass.domain.Question;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface QuestionRepository extends JpaRepository<Question, Long> {

    Optional<Question> findByCode(String code);

    List<Question> findByCodeIn(List<String> codes);

    /**
     * Questions applicable to a profile, using the Postgres array containment
     * operator so the filter runs in the database rather than in Java.
     *
     * <p>Native query because JPQL has no equivalent of {@code = ANY}.
     */
    @Query(value = """
            SELECT * FROM questions
            WHERE :profile = ANY (applicable_profiles)
            ORDER BY category, code
            """, nativeQuery = true)
    List<Question> findApplicableTo(@Param("profile") String profileTypeName);
}
