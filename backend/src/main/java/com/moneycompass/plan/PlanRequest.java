package com.moneycompass.plan;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.Set;

/**
 * The planner's inputs. Every field is optional: a missing amount starts from
 * the suggested one, a missing horizon from the user's own answer (or 10
 * years), and vehicles default to mutual funds plus direct shares.
 *
 * @param vehicles "mf" and/or "direct"
 * @param stepUp   yearly rise in the monthly amount, %
 */
public record PlanRequest(
        @DecimalMin("0") @DecimalMax("10000000") Double monthly,
        @Min(1) @Max(40) Integer years,
        Set<String> vehicles,
        @DecimalMin("0") @DecimalMax("50") Double stepUp
) {}
