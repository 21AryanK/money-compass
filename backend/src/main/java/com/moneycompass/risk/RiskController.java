package com.moneycompass.risk;

import com.moneycompass.domain.RiskBand;
import com.moneycompass.risk.dto.RiskProfileResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/risk")
public class RiskController {

    private final RiskService riskService;

    public RiskController(RiskService riskService) {
        this.riskService = riskService;
    }

    @PostMapping("/{sessionId}")
    public RiskProfileResponse compute(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        return riskService.computeOrFetch(sessionId, userId(jwt));
    }

    @GetMapping("/{sessionId}")
    public RiskProfileResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        return riskService.computeOrFetch(sessionId, userId(jwt));
    }

    /** A new draft of the rationale for the active band; earlier drafts are kept. */
    @PostMapping("/{sessionId}/regenerate")
    public RiskProfileResponse regenerate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        return riskService.regenerate(sessionId, userId(jwt));
    }

    /**
     * Plan around a different band than the calculated one. The calculated
     * band stays in the response as {@code riskBand}; the allocation,
     * rationale and investment plan follow {@code activeBand}.
     */
    @PutMapping("/{sessionId}/band")
    public RiskProfileResponse selectBand(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId,
                                          @RequestBody SelectBandRequest request) {
        return riskService.selectBand(sessionId, userId(jwt), request.band());
    }

    /** @param band the band to plan around, or null to go back to the calculated one */
    public record SelectBandRequest(RiskBand band) {}

    private UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
