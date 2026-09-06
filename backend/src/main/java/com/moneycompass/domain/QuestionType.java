package com.moneycompass.domain;

/** Shape of the expected answer value. Drives both validation and the UI control. */
public enum QuestionType {
    /** Exactly one option from {@code options}. */
    SINGLE,
    /** One or more options from {@code options}. */
    MULTI,
    /** A bare number, for example months of expenses saved. */
    NUMBER,
    /** An integer 1..5 self-assessment. */
    SCALE
}
