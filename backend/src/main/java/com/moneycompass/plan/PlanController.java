package com.moneycompass.plan;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * The investment planner. POST rather than GET because the inputs (amount,
 * horizon, vehicles, step-up) are a small document, and nothing is stored:
 * the same inputs always give the same plan.
 */
@RestController
@RequestMapping("/api/plan")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    @PostMapping("/{sessionId}")
    public PlanResponse plan(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId,
                             @Valid @RequestBody(required = false) PlanRequest request) {
        return planService.plan(sessionId, UUID.fromString(jwt.getSubject()), request);
    }
}
