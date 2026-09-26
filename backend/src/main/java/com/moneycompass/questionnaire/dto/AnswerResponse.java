package com.moneycompass.questionnaire.dto;

/** @param nextQuestion null once every eligible question has been answered */
public record AnswerResponse(QuestionDto nextQuestion, ProgressDto progress) {}
