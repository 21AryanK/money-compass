package com.moneycompass.assistant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

/**
 * One turn of the conversation.
 *
 * @param history     the conversation so far, oldest first; only the last few
 *                    turns are sent to the model
 * @param screen      "score", "risk" or "invest" — which screen the question
 *                    was asked from, used to tailor suggestions
 * @param planMonthly the monthly amount on the plan screen, if the user changed it
 * @param planYears   the plan horizon on screen, if the user changed it
 */
public record ChatRequest(
        @NotBlank @Size(max = 500) String message,
        @Size(max = 40) List<@Valid Turn> history,
        String screen,
        @DecimalMin("0") @DecimalMax("10000000") Double planMonthly,
        @Min(1) @Max(40) Integer planYears
) {

    /** @param role "user" or "assistant" */
    public record Turn(@NotBlank @Pattern(regexp = "user|assistant") String role,
                       @NotBlank @Size(max = 4000) String content) {}
}
