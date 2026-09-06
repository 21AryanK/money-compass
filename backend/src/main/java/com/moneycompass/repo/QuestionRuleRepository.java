package com.moneycompass.repo;

import com.moneycompass.domain.QuestionRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QuestionRuleRepository extends JpaRepository<QuestionRule, Long> {

    List<QuestionRule> findByQuestionCodeInOrderByPriorityAsc(List<String> questionCodes);

    List<QuestionRule> findAllByOrderByPriorityAsc();
}
