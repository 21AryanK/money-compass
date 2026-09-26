package com.moneycompass.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets the frontend show whether the configured AI providers are actually
 * reachable, separately from the deterministic scoring which never depends
 * on them.
 */
@RestController
@RequestMapping("/api/health")
public class AiHealthController {

    private final AiHealthService aiHealthService;

    public AiHealthController(AiHealthService aiHealthService) {
        this.aiHealthService = aiHealthService;
    }

    @GetMapping("/ai")
    public AiHealthResponse aiHealth() {
        return aiHealthService.check();
    }
}
