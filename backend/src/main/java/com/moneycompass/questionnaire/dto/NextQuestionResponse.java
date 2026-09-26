package com.moneycompass.questionnaire.dto;

/** @param question null once every eligible question has been answered */
public record NextQuestionResponse(QuestionDto question, ProgressDto progress) {}
