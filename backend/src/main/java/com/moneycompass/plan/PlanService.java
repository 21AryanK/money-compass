package com.moneycompass.plan;

import com.moneycompass.domain.RiskBand;
import com.moneycompass.domain.RiskProfile;
import com.moneycompass.engine.FinancialSnapshot;
import com.moneycompass.engine.SessionContext;
import com.moneycompass.engine.SnapshotCalculator;
import com.moneycompass.repo.RiskProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the investment plan for a session. It follows whichever band is
 * active — the calculated one, or the user's own pick on the Risk screen —
 * so choosing a band there actually changes the plan here.
 */
@Service
public class PlanService {

    private static final Set<String> VEHICLES = Set.of("mf", "direct");

    private final SessionContext sessionContext;
    private final RiskProfileRepository riskProfileRepository;
    private final SnapshotCalculator calculator;
    private final InvestmentPlanner planner;

    public PlanService(SessionContext sessionContext, RiskProfileRepository riskProfileRepository,
                       SnapshotCalculator calculator, InvestmentPlanner planner) {
        this.sessionContext = sessionContext;
        this.riskProfileRepository = riskProfileRepository;
        this.calculator = calculator;
        this.planner = planner;
    }

    @Transactional(readOnly = true)
    public PlanResponse plan(UUID sessionId, UUID userId, PlanRequest request) {
        SessionContext.Loaded ctx = sessionContext.load(sessionId, userId);
        FinancialSnapshot snap = calculator.snapshot(ctx.answers(), ctx.profile());
        RiskBand band = riskProfileRepository.findBySessionId(sessionId)
                .map(RiskProfile::getActiveBand)
                .orElseGet(() -> calculator.assessRisk(ctx.answers(), ctx.profile()).band());
        return build(snap, band, request == null ? new PlanRequest(null, null, null, null) : request);
    }

    /** Pure: also used by the assistant to answer "what does my plan look like". */
    public PlanResponse build(FinancialSnapshot snap, RiskBand band, PlanRequest req) {
        Map<String, Integer> allocation = SnapshotCalculator.ALLOCATIONS.get(band);
        double suggested = calculator.suggestedMonthly(snap);
        double monthly = req.monthly() == null ? suggested : req.monthly();
        int years = req.years() != null ? req.years() : snap.horizonYears() != null ? snap.horizonYears() : 10;
        Set<String> vehicles = new LinkedHashSet<>();
        if (req.vehicles() != null) req.vehicles().stream().filter(VEHICLES::contains).forEach(vehicles::add);
        if (vehicles.isEmpty()) {
            vehicles.add("mf");
            vehicles.add("direct");
        }
        double stepUp = req.stepUp() == null ? 0 : req.stepUp();

        Map<String, InvestmentPlanner.MixView> mixes = new LinkedHashMap<>();
        putIfPresent(mixes, "fund", planner.fundMix(monthly, allocation, vehicles, band, snap, years));
        putIfPresent(mixes, "direct", planner.directMix(monthly, allocation, vehicles, band, snap, years));
        putIfPresent(mixes, "debt", planner.debtMix(monthly, allocation, years, snap));
        putIfPresent(mixes, "gold", planner.goldMix(monthly, allocation, years));
        putIfPresent(mixes, "cash", planner.cashMix(monthly, allocation, snap));

        return new PlanResponse(monthly, years, vehicles, stepUp, band, allocation, suggested,
                snap.income(), snap.savingsRate(), snap.rateKnown(),
                planner.priorities(snap, calculator.waterfall(snap)),
                planner.project(monthly, years, allocation, vehicles, band, snap, stepUp),
                mixes, SnapshotCalculator.INFLATION_PCT);
    }

    private static void putIfPresent(Map<String, InvestmentPlanner.MixView> mixes, String key, InvestmentPlanner.MixView view) {
        if (view != null) mixes.put(key, view);
    }
}
