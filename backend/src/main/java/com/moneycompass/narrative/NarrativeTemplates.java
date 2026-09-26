package com.moneycompass.narrative;

import com.moneycompass.domain.ProfileType;
import com.moneycompass.domain.QuestionCategory;
import com.moneycompass.domain.RiskBand;
import com.moneycompass.engine.FinancialSnapshot;
import com.moneycompass.engine.SnapshotCalculator.CapacityFactor;
import com.moneycompass.engine.SnapshotCalculator.Waterfall;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Supplier;

import static com.moneycompass.engine.Money.amount;
import static com.moneycompass.engine.Money.inr;
import static com.moneycompass.engine.Money.roundTo;
import static com.moneycompass.engine.PlanMath.*;
import static com.moneycompass.engine.SnapshotCalculator.*;

/**
 * Narrative text built directly from the user's numbers — no model involved.
 * It has two jobs:
 *
 * <ol>
 *   <li><b>Grounding.</b> Every candidate point here is computed from the
 *       user's own answers and rupee figures. {@link NarrativeService} hands
 *       them to the model as the facts it may use, so the model writes the
 *       prose but never invents a number.</li>
 *   <li><b>Fallback.</b> When no provider answers, a draft is assembled from
 *       these points instead, labelled {@code template} so the screen can say
 *       so, rather than the results page failing outright.</li>
 * </ol>
 *
 * <p>Each narrative is a pool of candidate points ({@link Candidate}), plus
 * several framings for its summary ({@link Angle}). A first draft leads with
 * the highest-priority points; a regenerate penalises points already shown
 * (see {@link DraftMemory}), so it brings in new, still-relevant ones, while
 * {@code must} points — high-interest debt — always come back, reworded.
 */
@Component
public class NarrativeTemplates {

    /** A point a narrative can make. {@code text} holds alternative phrasings of the same point. */
    public record Candidate(String id, double priority, boolean must, List<String> text) {
        String first() {
            return text.getFirst();
        }
    }

    /** A framing for the summary paragraph; {@code build} is null when it doesn't apply to this user. */
    public record Angle(String id, Supplier<String> build) {}

    /** Everything a score narrative could say, before choosing. */
    public record ScorePools(List<Angle> angles, List<Candidate> strengths, List<Candidate> gaps,
                             List<Candidate> nextSteps, String bandTitle, String bandSub) {}

    public record RiskPools(List<Angle> angles, List<Candidate> considerations, List<Candidate> avoid) {}

    /** A chosen draft: the text, and which points and framing it used. */
    public record Chosen(Map<String, Object> content, List<String> pointIds, String angle) {}

    private static final Map<String, List<String>> CATEGORY_TIPS = Map.of(
            "BUDGETING", List.of(
                    "For budgeting, try a 'spending review Sunday': ten minutes once a week comparing what went out against what you planned.",
                    "Split your bank balance on payday — one account for fixed bills, one for everything else — so overspending shows up before the month ends."),
            "SAVING", List.of(
                    "For saving, move money out on the day it arrives rather than at month-end — what's left over is almost always less than planned.",
                    "Give each savings pot a name and a number (\"emergency fund — ₹X\"); named goals get funded far more reliably than a general 'savings'."),
            "DEBT", List.of(
                    "On debt, list every balance with its interest rate and pay the highest rate first while making minimums on the rest.",
                    "Pay any credit card bill in full, not the 'minimum due' — the minimum keeps the whole balance accruing interest at 3%+ a month."),
            "INVESTING", List.of(
                    "For investing, a single low-cost Nifty 50 or Nifty 500 index fund is a complete first step — no stock-picking needed.",
                    "Before investing in anything, check it's registered with SEBI (or RBI, for deposits) — it takes two minutes and rules out most scams."),
            "COMPOUNDING", List.of(
                    "To build intuition for compounding, use the Rule of 72: divide 72 by the annual return to get the years it takes money to double.",
                    "Watch for lifestyle creep: when your income rises, send at least half of each raise straight to savings before you get used to spending it."),
            "RISK", List.of(
                    "On risk, decide now what you'll do in a 20–30% fall and write it down — a plan made calmly beats a decision made in a panic.",
                    "Match money to time: anything you need within 3 years shouldn't be in equity, however good the market looks."),
            "RETIREMENT", List.of(
                    "For retirement, find out your current EPF and PPF balances — most people underestimate what's already building.",
                    "Treat retirement saving as a fixed bill, not a leftover: a set amount on a set date, raised a little every year."));

    // =================================================================== score

    public static String[] bandCopy(int total) {
        if (total < 40) return new String[]{"Just getting started", "The fundamentals are still forming — that's exactly what this assessment is for."};
        if (total < 60) return new String[]{"Building the basics", "You've got real footing in a few areas, with clear room to close the rest."};
        if (total < 80) return new String[]{"Solid foundation", "You're handling the basics well, with room to sharpen a few habits."};
        return new String[]{"Strong literacy", "You're ahead of most — the gaps left are refinements, not fundamentals."};
    }

    public static String label(String category) {
        return category.charAt(0) + category.substring(1).toLowerCase();
    }

    public ScorePools scorePools(int total, Map<String, Integer> breakdown, ProfileType profile,
                                 FinancialSnapshot s, Waterfall flow, Random rnd) {
        String[] band = bandCopy(total);
        List<String> ranked = breakdown.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .map(Map.Entry::getKey).toList();
        String bestCat = ranked.getFirst(), worstCat = ranked.getLast();
        String best = label(bestCat).toLowerCase(), worst = label(worstCat).toLowerCase();
        int bestPct = breakdown.get(bestCat), worstPct = breakdown.get(worstCat);
        String profileNoun = profile == ProfileType.STUDENT ? "as a student" : profile == ProfileType.RETIREE ? "in retirement" : "at your career stage";
        boolean incomeKnown = s.incomeKnown();
        Double monthlySave = incomeKnown ? s.saving() : null;
        boolean needsTerm = ("B".equals(s.dependents()) || "C".equals(s.dependents()))
                && ("A".equals(s.termCover()) || "B".equals(s.termCover()) || "?".equals(s.termCover()));
        Set<String> missedCodes = new HashSet<>();
        s.missedTopics().forEach(t -> missedCodes.add(t.code()));

        // ---- the single most important money lever, used by several framings
        String lever;
        if (Boolean.TRUE.equals(s.highInterestDebt())) {
            lever = pick(rnd,
                    "the first priority is the high-interest debt: at around " + fmt(HIGH_INTEREST_PCT) + "% a year it grows faster than anything in your plan can earn.",
                    "nothing matters more right now than the high-interest debt — at roughly " + fmt(HIGH_INTEREST_PCT) + "% a year, clearing it beats any investment return you could find.");
        } else if (incomeKnown && s.efGap() > 0) {
            lever = pick(rnd,
                    "your biggest lever is the emergency fund: about " + amount(s.efHave()) + " today against a " + amount(s.efTarget())
                            + " target (" + s.efTargetMonths() + " months of your estimated spending).",
                    "the emergency fund comes first — you're " + amount(s.efGap()) + " short of the " + s.efTargetMonths()
                            + "-month cushion (" + amount(s.efTarget()) + ") that keeps a surprise bill from becoming debt.");
        } else if (incomeKnown && s.rateKnown() && s.savingsRate() < 20) {
            lever = pick(rnd,
                    "your biggest lever is the savings rate: " + Math.round(s.savingsRate()) + "% of " + amount(s.income()) + " is "
                            + amount(s.saving()) + " a month, and 20% would be " + amount(s.income() * 0.2) + ".",
                    "the savings rate is where the money is: moving from " + Math.round(s.savingsRate()) + "% to 20% of your income adds "
                            + amount(s.income() * (20 - s.savingsRate()) / 100) + " a month to everything else in this plan.");
        } else if (needsTerm) {
            lever = "the gap that matters most is protection — people rely on your income and there's no adequate term cover behind it yet.";
        } else {
            lever = null;
        }

        List<String> habitFacts = new ArrayList<>();
        if ("D".equals(s.tracking())) habitFacts.add("you track spending in detail");
        else if ("A".equals(s.tracking()) || "B".equals(s.tracking())) habitFacts.add("you don't track spending closely yet");
        if ("C".equals(s.autoSave()) || "D".equals(s.autoSave())) habitFacts.add("your saving runs on autopilot");
        else if ("A".equals(s.autoSave()) || "B".equals(s.autoSave())) habitFacts.add("saving happens only when there's something left over");
        if (s.dropScale() != null) habitFacts.add(s.dropScale() >= 4 ? "you'd hold steady through a 20% market fall"
                : s.dropScale() <= 2 ? "you'd be tempted to sell in a 20% fall" : "you'd hesitate, but probably hold, in a 20% fall");
        List<String> middle = ranked.size() > 2 ? ranked.subList(1, ranked.size() - 1).stream()
                .map(c -> label(c).toLowerCase() + " (" + breakdown.get(c) + "%)").toList() : List.of();

        List<Angle> angles = new ArrayList<>();
        angles.add(new Angle("overview", () -> pick(rnd,
                "You scored " + total + " out of 100.",
                "Your literacy score comes out to " + total + " out of 100.",
                total + " out of 100 — here's what that reflects.")
                + " Your strongest area is " + best + " at " + bestPct + "%; " + worst + " is the softest at " + worstPct
                + "% — a natural next focus " + profileNoun + ". " + (lever != null ? "In money terms, " + lever : band[1])));
        angles.add(new Angle("lever", lever == null ? null : () ->
                "If you take one thing from this, make it this: " + lever + " The score — " + total + "/100 — matters less than that. It does show "
                        + best + " (" + bestPct + "%) as solid ground, and " + worst + " (" + worstPct + "%) as the area where a little reading pays off fastest."));
        angles.add(new Angle("future", monthlySave == null || monthlySave <= 0 || profile == ProfileType.RETIREE ? null : () ->
                "Look at it through time. The roughly " + amount(monthlySave) + " you put aside each month, invested at an assumed " + fmt(RATE_MF)
                        + "% a year, would grow to about " + amount(sipFutureValue(monthlySave, RATE_MF, 10)) + " in 10 years and "
                        + amount(sipFutureValue(monthlySave, RATE_MF, 20)) + " in 20. Your score of " + total + "/100 says the knowledge behind that is "
                        + band[0].toLowerCase() + "; shoring up " + worst + " (" + worstPct + "%) is what protects that trajectory."));
        angles.add(new Angle("spread", ranked.size() < 3 || bestPct - worstPct < 25 ? null : () ->
                "A total of " + total + "/100 hides a real spread: " + best + " at " + bestPct + "% against " + worst + " at " + worstPct + "%"
                        + (middle.isEmpty() ? "" : ", with " + listJoin(middle.subList(0, Math.min(3, middle.size()))) + " in between")
                        + ". Uneven scores like this are good news — lifting the weakest area moves the total much faster than polishing the strongest."));
        angles.add(new Angle("habits", habitFacts.size() < 2 ? null : () ->
                "Knowledge is only half of this; habits are the rest. From your answers, " + listJoin(habitFacts) + ". Paired with a score of "
                        + total + "/100 — strongest in " + best + ", weakest in " + worst + " — "
                        + ("A".equals(s.autoSave()) || "B".equals(s.autoSave()) ? "automating your saving is likely the cheapest win available."
                        : "A".equals(s.tracking()) || "B".equals(s.tracking()) ? "a month of honest spending tracking would sharpen every other number here."
                        : "your habits are doing more work than the score alone suggests.")));
        angles.add(new Angle("profile", () -> {
            String frame = profile == ProfileType.STUDENT
                    ? "As a student, your biggest financial asset is time, not money — small habits started now compound for decades."
                    : profile == ProfileType.RETIREE
                    ? "In retirement the job changes from growing money to making it last, and to protecting it from health costs, inflation and scams."
                    : "Mid-career, the decisions that matter most are protecting your income, keeping debt in check and letting long-term investments compound.";
            return frame + " Against that, your " + total + "/100 shows " + best + " (" + bestPct + "%) is in good shape and " + worst + " ("
                    + worstPct + "%) needs attention" + (lever != null ? "; practically, " + lever : ".");
        }));

        // ---- strengths
        List<Candidate> strengths = new ArrayList<>();
        if (s.efMonths() != null && s.efMonths() >= s.efTargetMonths()) strengths.add(c("ef-ok", 9,
                "Your emergency fund covers about " + Math.round(s.efMonths()) + " months — at or above the " + s.efTargetMonths() + "-month target for your situation.",
                "With roughly " + Math.round(s.efMonths()) + " months of spending set aside, a job loss or medical bill wouldn't force you into debt."));
        if (Boolean.FALSE.equals(s.highInterestDebt())) strengths.add(c("no-debt", 8,
                "No high-interest debt, so nothing is quietly compounding against you.",
                "You carry no high-interest debt — which means every rupee you invest is working for you, not paying someone else's interest."));
        if (s.rateKnown() && s.savingsRate() >= 20) strengths.add(c("save-rate", 9,
                "You save " + Math.round(s.savingsRate()) + "% of your income" + (incomeKnown ? " (about " + amount(s.saving()) + " a month)" : "") + " — above the common 20% benchmark.",
                "A " + Math.round(s.savingsRate()) + "% savings rate puts you ahead of most households — it's the single number that drives long-term wealth."));
        if ("C".equals(s.autoSave()) || "D".equals(s.autoSave())) strengths.add(c("auto", 6,
                "Your saving is automated, so it happens whether or not you remember.",
                "Automatic saving means you never have to rely on willpower at month-end — the most reliable habit there is."));
        if ("D".equals(s.tracking())) strengths.add(c("tracking", 6,
                "You track spending in detail and review it every month.",
                "Monthly, detailed tracking means your budget is based on facts, not guesses — most people never get there."));
        if ("C".equals(s.tracking())) strengths.add(c("tracking-some", 3,
                "You already use a spreadsheet or app for spending — a monthly review would turn that into a real budgeting habit."));
        if ("D".equals(s.termCover())) strengths.add(c("term", 8,
                "Your family has term cover of 10× your income or more — the single most important protection when others depend on you.",
                "Adequate term cover means the people who rely on you are protected, whatever happens."));
        if (s.dropScale() != null && s.dropScale() >= 4) strengths.add(c("steady", 7,
                "You'd stay invested through a 20% fall — the behaviour long-term returns depend on most.",
                "Holding steady in a downturn is rarer than knowing what an index fund is — and it's worth more."));
        if ("D".equals(s.healthCover())) strengths.add(c("health", 6,
                "You have your own health policy plus a top-up — a strong, cost-efficient way to cover a large hospital bill."));
        if ("C".equals(s.healthCover())) strengths.add(c("health", 5,
                "You have health cover of your own, which stays with you whatever happens to your job."));
        if ("A".equals(s.emiBand())) strengths.add(c("no-emi", 5, "None of your income goes to EMIs, which keeps your monthly cash flow flexible."));
        if ("B".equals(s.emiBand())) strengths.add(c("low-emi", 3, "EMIs take under 20% of your income — comfortably within what lenders consider healthy."));
        if (s.age() != null && s.age() < 30 && profile != ProfileType.RETIREE) strengths.add(c("young", 5,
                "At " + s.age() + ", you have " + (s.yearsToRetire() != null && s.yearsToRetire() > 0 ? s.yearsToRetire() + "" : "many")
                        + " years for compounding to work — time is the one input money can't buy back.",
                "Starting at " + s.age() + " gives you a head start most people only wish they'd had."));
        if (monthlySave != null && monthlySave > 0 && profile != ProfileType.RETIREE) strengths.add(c("compounding", 4,
                "The " + amount(monthlySave) + " you save each month is already a meaningful engine — at " + fmt(RATE_MF) + "%, 15 years of it would be about "
                        + amount(sipFutureValue(monthlySave, RATE_MF, 15)) + "."));
        if (s.missedTopics().isEmpty()) strengths.add(c("no-misses", 5,
                "You didn't miss a single knowledge question — the concepts are there; it's now about applying them."));
        if ("D".equals(s.nomination())) strengths.add(c("will", 6, "Your nominations and will are in place, which spares your family months of paperwork."));
        strengths.add(c("best-cat", 4,
                capitalize(best) + " is your clearest strength — worth protecting as your income changes.",
                "Your " + best + " answers stood out — whatever you're doing there is working."));
        if (ranked.size() > 2 && breakdown.get(ranked.get(1)) >= 60) strengths.add(c("second-cat", 3,
                label(ranked.get(1)) + " (" + breakdown.get(ranked.get(1)) + "%) is another area where your answers were solid."));

        // ---- gaps
        List<Candidate> gaps = new ArrayList<>();
        boolean noPlan = "A".equals(s.payoffPlan()) || "B".equals(s.payoffPlan());
        if (Boolean.TRUE.equals(s.highInterestDebt())) gaps.add(must("debt", 10,
                "High-interest debt at roughly " + fmt(HIGH_INTEREST_PCT) + "% a year" + (noPlan ? ", with no real payoff plan behind it yet" : "") + ".",
                "The high-interest balance is costing you around " + fmt(HIGH_INTEREST_PCT) + "% a year" + (noPlan ? " — and without a payoff plan, it's unlikely to shrink on its own" : "") + "."));
        if (s.efMonths() == null) gaps.add(c("ef-unknown", 8,
                "You weren't sure how big your emergency cushion is — we've planned as if there's none, so it's worth checking.",
                "Not knowing your emergency-fund size is a gap in itself: you can't lean on a cushion you haven't measured."));
        if (s.efMonths() != null && s.efMonths() < s.efTargetMonths()) {
            String m = oneDecimal(s.efMonths());
            gaps.add(c("ef-short", 9,
                    "Your emergency fund covers about " + m + " of the " + s.efTargetMonths() + " months you'd want" + (incomeKnown ? " — a gap of roughly " + amount(s.efGap()) : "") + ".",
                    "With " + m + " months set aside, an unexpected expense could push you onto a credit card" + (incomeKnown ? " — you'd want about " + amount(s.efGap()) + " more" : "") + "."));
        }
        if (needsTerm) gaps.add(c("term", 9,
                "People depend on your income, but there's no adequate term life cover behind it"
                        + ("B".equals(s.termCover()) ? " — endowment and money-back plans give far less cover for the same premium" : "") + ".",
                "If your income stopped, your dependents would have little to fall back on — term cover is the missing piece."));
        if ("A".equals(s.healthCover()) || "?".equals(s.healthCover())) gaps.add(c("health", 8,
                "No health insurance of your own — one hospital stay could wipe out your savings.",
                "Without health cover, medical inflation (often 10%+ a year) is a risk no emergency fund can fully absorb."));
        if ("B".equals(s.healthCover())) gaps.add(c("health", 6,
                "Your only health cover comes through your employer or college, and it ends when you leave.",
                "Relying only on group health cover leaves a gap between jobs — or after graduation — exactly when you'd least want one."));
        List<String> gapTopics = s.missedTopics().stream().limit(4).map(FinancialSnapshot.MissedTopic::topic).toList();
        if (gapTopics.size() > 1) gaps.add(c("topics", 5,
                "Topics worth ten minutes each: " + listJoin(gapTopics) + (s.missedTopics().size() > 4 ? ", plus " + (s.missedTopics().size() - 4) + " more" : "") + "."));
        if (!s.missedTopics().isEmpty()) {
            FinancialSnapshot.MissedTopic t = s.missedTopics().getFirst();
            gaps.add(c("topic-" + t.code(), 3, (t.unknown() ? "You weren't sure about " : "The question on ") + t.topic()
                    + (t.unknown() ? "" : " tripped you up") + " — it's a concept that shows up in real decisions more often than it seems."));
        }
        if (s.rateKnown() && s.savingsRate() < 10) gaps.add(c("low-save", 7,
                "Saving " + Math.round(s.savingsRate()) + "% of income leaves little room for goals or shocks.",
                "At " + Math.round(s.savingsRate()) + "%, your savings rate is the bottleneck — every other goal draws from it."));
        if (s.rateKnown() && s.savingsRate() >= 10 && s.savingsRate() < 20) gaps.add(c("mid-save", 4,
                "Saving " + Math.round(s.savingsRate()) + "% is a real start, but still short of the 20% that most long-term plans assume."));
        if ("C".equals(s.emiBand()) || "D".equals(s.emiBand())) gaps.add(c("emi", 7, "D".equals(s.emiBand())
                ? "Over 40% of your income goes to EMIs — lenders treat that as stretched, and it leaves little for anything else."
                : "EMIs take 20–40% of your income — manageable, but any new loan would tip it into stretched territory."));
        if ("A".equals(s.tracking()) || "B".equals(s.tracking())) gaps.add(c("no-track", 5,
                "You don't track spending closely — without it, your savings rate is a guess rather than a decision.",
                "Spending is estimated rather than tracked, which makes it hard to spot where money quietly leaks."));
        if ("A".equals(s.autoSave()) || "B".equals(s.autoSave())) gaps.add(c("no-auto", 5,
                "Saving depends on what's left at month-end, which is usually less than planned.",
                "Without an automatic transfer, saving competes with every other expense — and tends to lose."));
        if (s.dropScale() != null && s.dropScale() <= 2) gaps.add(c("panic", 6,
                "You'd likely sell in a 20% fall — that's how temporary losses become permanent ones.",
                "The urge to sell in a downturn is the most expensive habit in investing; it's worth planning for now."));
        if (s.corpusMultiple() != null && s.corpusMultiple() < 25 && profile == ProfileType.PROFESSIONAL) gaps.add(c("corpus", 6,
                "You estimated a retirement corpus of about " + Math.round(s.corpusMultiple()) + "× annual spending — below the ~25× most planners use."));
        if (s.runwayYears() != null && s.runwayYears() < 25) gaps.add(c("runway", 7,
                "You expect your corpus to last about " + Math.round(s.runwayYears()) + " years — short of a 25–30 year retirement."));
        gaps.add(c("worst-cat", 3,
                capitalize(worst) + " is pulling your overall score down the most — small, repeatable changes there move it fastest.",
                "At " + worstPct + "%, " + worst + " is the category with the most headroom."));

        // ---- next steps
        List<Candidate> next = new ArrayList<>();
        if (needsTerm) next.add(c("term", 9,
                "Get a pure term plan" + (incomeKnown ? " of around " + amount(s.income() * 12 * 10) + " (10× your annual take-home)" : " of about 10× your annual income")
                        + " — for someone healthy, it usually costs far less than any investment-linked policy.",
                "Compare pure term plans online this week — cover of " + (incomeKnown ? amount(s.income() * 12 * 10) : "10× your income")
                        + " is the benchmark, and premiums are lowest the younger you buy."));
        if (flow != null && flow.toDebt() > 0) next.add(must("debt-pay", 10,
                "Put about " + inr(flow.toDebt()) + " a month toward the high-interest balance first — clearing it is a guaranteed ~" + fmt(HIGH_INTEREST_PCT) + "% return.",
                "Direct " + inr(flow.toDebt()) + " a month at the high-interest debt before investing anything — no fund reliably beats a ~" + fmt(HIGH_INTEREST_PCT) + "% saving."));
        if (Boolean.TRUE.equals(s.highInterestDebt()) && flow == null) next.add(must("debt-pay", 10,
                "List every high-interest balance with its rate and put every spare rupee on the most expensive one first.",
                "Ask your bank about moving the balance to a cheaper personal loan — anything well below " + fmt(HIGH_INTEREST_PCT) + "% is an instant saving."));
        if (flow != null && flow.toEf() > 0) {
            String months = flow.efMonthsToFill() + " month" + (flow.efMonthsToFill() == 1 ? "" : "s");
            next.add(c("ef-fill", 8,
                    "Move " + inr(flow.toEf()) + " a month into a liquid fund or sweep-in FD; at that pace you reach " + amount(s.efTarget()) + " in about " + months
                            + (flow.toDebt() > 0 ? " — sooner once the debt is cleared and its share moves here" : "") + ".",
                    "Set up a " + inr(flow.toEf()) + "/month transfer to a separate emergency account — about " + months + " to a full " + s.efTargetMonths() + "-month cushion."));
        }
        if (incomeKnown && s.rateKnown() && s.savingsRate() < 20) {
            double extra = s.income() * (20 - s.savingsRate()) / 100;
            next.add(c("to-20", 7, "Going from " + Math.round(s.savingsRate()) + "% to 20% saved means " + amount(extra) + " more a month — about "
                    + amount(sipFutureValue(extra, RATE_MF, 10)) + " after 10 years at " + fmt(RATE_MF) + "%."));
        }
        if (profile == ProfileType.STUDENT && flow != null) next.add(c("student-sip", 7,
                "A " + inr(flow.toInvest()) + "/month index-fund SIP started now could reach about " + amount(sipFutureValue(flow.toInvest(), RATE_MF, 10))
                        + " in 10 years; starting 5 years later, about " + amount(sipFutureValue(flow.toInvest(), RATE_MF, 5)) + "."));
        if (profile == ProfileType.STUDENT && flow == null) next.add(c("student-sip", 6,
                "Start (or top up) a small SIP in an index fund — starting small now matters more than starting big later."));
        if (profile == ProfileType.STUDENT) next.add(c("credit", 4,
                "If you get a credit card, use it for one small bill a month and pay it in full — it builds a credit score you'll need for any future loan."));
        if (profile == ProfileType.PROFESSIONAL) {
            if (missedCodes.contains("investing_tax_regime")) next.add(c("tax", 7,
                    "Compare both tax regimes before your employer's declaration deadline: under the new regime, salaried income up to ₹12.75 lakh pays no tax, while the old one only wins if your deductions are large."));
            else if (missedCodes.contains("investing_80c_options") || missedCodes.contains("investing_elss_lockin") || missedCodes.contains("retirement_nps_tax"))
                next.add(c("tax", 6, "If you're on the old tax regime, check 80C (up to ₹1.5 lakh a year) and NPS under 80CCD(1B) (₹50,000 more) before the financial year ends."));
            next.add(c("epf", missedCodes.contains("retirement_epf_job_change") ? 5 : 2,
                    "When you change jobs, transfer your EPF rather than withdrawing it — early withdrawals can be taxable and reset years of compounding."));
            if (s.spend() != null) {
                double corpus = s.spend() * 12 * 25;
                String sipLine = "";
                if (s.yearsToRetire() != null && s.yearsToRetire() > 0) {
                    // Work in today's rupees: the real return is what's left after inflation.
                    double realPct = ((1 + RATE_MF / 100) / (1 + INFLATION_PCT / 100) - 1) * 100;
                    double needed = corpus / sipFutureValue(1, realPct, s.yearsToRetire());
                    sipLine = " To build that by " + RETIREMENT_AGE + " starting from nothing, you'd invest about " + amount(needed)
                            + " a month (in today's money, raised with inflation each year) — less for whatever you've already saved.";
                }
                next.add(c("corpus", 6, "A common retirement target is 25× annual spending — about " + amount(corpus) + " in today's money for you"
                        + (s.corpusMultiple() != null && s.corpusMultiple() < 25 ? " (you estimated " + Math.round(s.corpusMultiple()) + "×, which may fall short)" : "")
                        + "." + sipLine));
            }
        }
        if (profile == ProfileType.RETIREE) {
            if (s.runwayYears() != null && s.runwayYears() < 25) next.add(c("withdraw", 8,
                    "You expect your corpus to last about " + Math.round(s.runwayYears()) + " years — test a slightly lower monthly withdrawal against a 25–30 year retirement."));
            if ("A".equals(s.healthFund()) || "B".equals(s.healthFund()) || "?".equals(s.healthFund())) next.add(c("med-fund", 7,
                    "Set aside a medical fund separate from your corpus, or top up health cover, so one hospital bill can't force a sale."));
            if (s.nomination() != null && !"D".equals(s.nomination())) next.add(c("nominee", 7,
                    "Add nominees on every bank account, FD and investment, and write a will — it spares your family months of paperwork and disputes."));
            next.add(c("scss", missedCodes.contains("retiree_scss_awareness") ? 6 : 3,
                    "Look at the Senior Citizens' Savings Scheme for part of your debt allocation — government-backed, with interest paid every quarter."));
            next.add(c("scam", missedCodes.contains("retiree_scam_digital_arrest") ? 7 : 3,
                    "Treat any caller claiming to be police, CBI or your bank and asking you to move money as a scam — there is no such thing as a 'digital arrest'."));
            if (missedCodes.contains("retiree_form_15h")) next.add(c("15h", 5,
                    "If your total income is below the taxable limit, submit Form 15H to each bank every April so TDS isn't deducted from your FD interest."));
        }
        if (("A".equals(s.autoSave()) || "B".equals(s.autoSave())) && profile != ProfileType.RETIREE) next.add(c("automate", 6,
                "Set up a standing transfer on payday" + (monthlySave != null && monthlySave > 0 ? " for " + inr(Math.max(500, roundTo(monthlySave, 500))) : "") + " — save first, then spend what's left.",
                "Automate one thing this week: an SIP or recurring deposit dated the day after your money arrives."));
        if ("A".equals(s.tracking()) || "B".equals(s.tracking())) next.add(c("track", 5,
                "For one month, log every spend over ₹200 — just amount and category. It's usually enough to find a leak you didn't know about."));
        if (incomeKnown && profile != ProfileType.RETIREE) next.add(c("503020", 3,
                "Run a quick 50/30/20 check on your " + amount(s.income()) + ": about " + amount(s.income() * 0.5) + " for needs, " + amount(s.income() * 0.3)
                        + " for wants, " + amount(s.income() * 0.2) + " for saving — then see which bucket your real spending overflows."));
        if (flow != null && flow.toInvest() > 0 && profile != ProfileType.RETIREE) {
            double a = sipFutureValue(flow.toInvest(), RATE_MF, 20), b = sipFutureValue(flow.toInvest(), RATE_MF, 19);
            next.add(c("delay", 4, "Don't wait for the 'right time': " + inr(flow.toInvest()) + "/month for 20 years is about " + amount(a) + " at "
                    + fmt(RATE_MF) + "%; starting a year later costs roughly " + amount(a - b) + " of that."));
            next.add(c("step-up", 4, "Raise your SIP by 10% each year as your income grows: " + inr(flow.toInvest()) + "/month stepped up yearly reaches about "
                    + amount(stepUpFutureValue(flow.toInvest(), RATE_MF, 15, 10)) + " in 15 years, against "
                    + amount(sipFutureValue(flow.toInvest(), RATE_MF, 15)) + " if it stays flat."));
        }
        if (monthlySave != null && monthlySave > 0) next.add(c("rule72", 3,
                "Rule of 72: at " + fmt(RATE_MF) + "% a year, money doubles roughly every " + pct(72 / RATE_MF) + " years — so this month's "
                        + amount(monthlySave) + " could be about " + amount(monthlySave * 4) + " in around " + Math.round(144 / RATE_MF) + " years."));
        if (incomeKnown && s.efHave() != null && s.efHave() > 0) next.add(c("ef-where", 3,
                "Keep the emergency fund where it earns something: " + amount(s.efHave()) + " in a plain savings account at ~3% loses roughly "
                        + amount(s.efHave() * (INFLATION_PCT - 3) / 100) + " of buying power a year to " + fmt(INFLATION_PCT)
                        + "% inflation; a liquid fund or sweep-in FD narrows that."));
        for (int i = 0; i < Math.min(3, s.missedTopics().size()); i++) {
            FinancialSnapshot.MissedTopic t = s.missedTopics().get(i);
            next.add(c("learn-" + t.code(), 3 - i * 0.5, "Spend ten minutes this week on " + t.topic() + " — it came up in your answers and it's quick to get right."));
        }
        List<String> weakest = new ArrayList<>(ranked.subList(Math.max(0, ranked.size() - 2), ranked.size()));
        Collections.reverse(weakest);
        for (int i = 0; i < weakest.size(); i++) {
            List<String> tips = CATEGORY_TIPS.getOrDefault(weakest.get(i), List.of());
            for (int j = 0; j < tips.size(); j++) next.add(c("tip-" + weakest.get(i) + j, 2.5 - i - j * 0.5, tips.get(j)));
        }
        next.add(c("worst-number", 1, "Put one concrete number behind " + worst + " this month — an amount, a date, or an account, not just an intention."));

        return new ScorePools(angles, strengths, gaps, next, band[0], band[1]);
    }

    /** A complete score narrative drawn from the pools. */
    public Chosen chooseScore(ScorePools pools, DraftMemory mem) {
        Angle angle = chooseAngle(pools.angles(), mem);
        List<String> ids = new ArrayList<>();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("summary", angle.build().get());
        content.put("strengths", chooseFrom(pools.strengths(), 3, mem, ids));
        content.put("gaps", chooseFrom(pools.gaps(), 3, mem, ids));
        content.put("nextSteps", chooseFrom(pools.nextSteps(), 4, mem, ids));
        return new Chosen(content, ids, angle.id());
    }

    // ==================================================================== risk

    public RiskPools riskPools(int tolerance, int capacity, RiskBand band, Map<String, Integer> al,
                               List<CapacityFactor> factors, FinancialSnapshot s, ProfileType profile,
                               double monthly, Random rnd) {
        String bandLower = band.name().toLowerCase();
        int gapSize = Math.abs(tolerance - capacity);
        int bi = band.ordinal();
        RiskBand lower = bi > 0 ? RiskBand.values()[bi - 1] : null;
        RiskBand higher = bi < RiskBand.values().length - 1 ? RiskBand.values()[bi + 1] : null;
        double blended = blendedReturn(al);
        double crashDrop = al.get("equity") * 0.3;

        List<CapacityFactor> drags = factors.stream().skip(1).filter(f -> f.delta() < 0)
                .sorted(Comparator.comparingInt(CapacityFactor::delta)).toList();
        String dragLine = !drags.isEmpty() && capacity <= tolerance
                ? " The biggest thing holding capacity back is: " + lowerFirst(drags.get(0).label()) + " (" + drags.get(0).delta() + " points)"
                + (drags.size() > 1 ? ", followed by " + lowerFirst(drags.get(1).label()) + " (" + drags.get(1).delta() + ")" : "") + "."
                : "";
        String gapLine = gapSize >= 20
                ? "There's a real gap between how you'd react and what your finances can currently absorb — the allocation leans toward the more cautious of the two."
                : "The two are reasonably aligned, which makes this allocation a fairly direct read of your answers.";

        List<Angle> angles = new ArrayList<>();
        angles.add(new Angle("mechanics", () -> pick(rnd,
                "Your stated tolerance (" + tolerance + "/100) and financial capacity (" + capacity + "/100) combine to "
                        + (band == RiskBand.AGGRESSIVE ? "an " : "a ") + bandLower + " band, since capacity can only cap the band, never raise it.",
                "Weighing your tolerance of " + tolerance + "/100 against a capacity of " + capacity + "/100 lands you in the " + bandLower + " band — the lower of the two always wins.")
                + " " + gapLine + " The suggested split of " + al.get("equity") + "% equity, " + al.get("debt") + "% debt, " + al.get("gold")
                + "% gold and " + al.get("cash") + "% cash is a starting point to adjust, not a target to hit exactly." + dragLine));
        angles.add(new Angle("rupees", monthly <= 0 ? null : () ->
                "In rupees, the " + bandLower + " band turns " + inr(monthly) + " a month into roughly " + inr(roundTo(monthly * al.get("equity") / 100, 100))
                        + " for equity, " + inr(roundTo(monthly * al.get("debt") / 100, 100)) + " for debt, " + inr(roundTo(monthly * al.get("gold") / 100, 100))
                        + " for gold and " + inr(roundTo(monthly * al.get("cash") / 100, 100)) + " kept as cash. Over the long run a mix like this assumes about "
                        + pct(blended) + "% a year — illustrative, not promised. It comes from a tolerance of " + tolerance + " and a capacity of " + capacity
                        + ", with the lower one setting the band." + dragLine));
        angles.add(new Angle("stress", () -> {
            String reaction = s.dropScale() == null ? ""
                    : s.dropScale() >= 4 ? " You said you'd hold through a 20% fall, which is exactly what this band needs from you."
                    : s.dropScale() <= 2 ? " You said a 20% fall would tempt you to sell — worth knowing before you commit to more equity than this."
                    : " You said you'd hesitate in a 20% fall; the debt and gold slices are there to make holding easier.";
            return "Picture a bad year: if equities fell 30% and everything else held flat, a " + bandLower + " portfolio would drop about "
                    + Math.round(crashDrop) + "% — on ₹1 lakh, roughly " + inr(roundTo(1000 * crashDrop, 100)) + "." + reaction
                    + " That's the trade this band makes for an assumed long-run return of about " + pct(blended) + "% a year.";
        }));
        angles.add(new Angle("neighbours", lower == null && higher == null ? null : () -> {
            List<String> parts = new ArrayList<>();
            if (lower != null) parts.add(capitalize(lower.name().toLowerCase()) + " holds " + ALLOCATIONS.get(lower).get("equity") + "% equity");
            if (higher != null) parts.add(capitalize(higher.name().toLowerCase()) + " holds " + ALLOCATIONS.get(higher).get("equity") + "%");
            return "It helps to see the bands either side: " + String.join(", while ", parts) + "; " + bandLower + " sits at " + al.get("equity")
                    + "%. You land here because your tolerance is " + tolerance + " and your capacity " + capacity
                    + " — and capacity can only cap the band, never lift it. " + gapLine + dragLine;
        }));
        angles.add(new Angle("horizon", s.horizonYears() == null ? null : () ->
                "Time matters as much as temperament. You expect to need this money in about " + s.horizonYears() + " year" + (s.horizonYears() > 1 ? "s" : "")
                        + (s.horizonYears() <= 3
                        ? " — too short for equity to reliably recover from a fall, so treat the " + al.get("equity") + "% equity share as a ceiling, not a floor."
                        : s.horizonYears() >= 10
                        ? " — long enough for equity's ups and downs to even out, so the " + al.get("equity") + "% equity share has time to do its work."
                        : " — a middle distance where a " + al.get("equity") + "% equity share, rebalanced yearly, is a sensible compromise.")
                        + " Your tolerance (" + tolerance + ") and capacity (" + capacity + ") put you in the " + bandLower + " band." + dragLine));

        List<Candidate> considerations = new ArrayList<>();
        considerations.add(c("rebalance", 6,
                "Rebalance back to these percentages roughly once a year, or after a large move in any one asset class.",
                "Once a year, move money from whatever has grown most back to what has lagged — it's a disciplined way to buy low and sell high."));
        if (profile == ProfileType.RETIREE) {
            considerations.add(c("bucket", 9, s.spend() != null
                    ? "Keep about " + amount(s.spend() * 24) + " — two years of your estimated spending — in debt and cash, so a market dip never forces a sale."
                    : "Keep enough in debt and cash to cover a couple of years of withdrawals, so a market dip never forces a sale."));
            considerations.add(c("swp", 5, "Draw income through a systematic withdrawal plan from the debt side, topping it up from equity only after good years."));
        } else {
            boolean shortHorizon = s.horizonYears() != null && s.horizonYears() <= 3;
            if (shortHorizon) considerations.add(c("short", 9, "You'll need most of this money within " + s.horizonYears() + " year"
                    + (s.horizonYears() > 1 ? "s" : "") + " — keep that part out of equity entirely, whatever the band says."));
            else considerations.add(c("grow", 5, "Increase the equity share gradually as your emergency fund and income stability improve."));
            if (s.age() != null) {
                int ruleEquity = Math.max(0, Math.min(100, 100 - s.age()));
                considerations.add(c("age-rule", 5, "The \"100 minus your age\" rule of thumb would put about " + ruleEquity + "% in equity at " + s.age()
                        + "; this plan holds " + al.get("equity") + "%" + (Math.abs(ruleEquity - al.get("equity")) >= 20
                        ? ", because your answers about risk and your finances point " + (al.get("equity") < ruleEquity ? "lower" : "higher") + "."
                        : " — close to it.")));
            }
            considerations.add(c("sip", 4, "Invest the equity part through a monthly SIP rather than a lump sum — it smooths out your entry price without any timing decisions."));
        }
        considerations.add(c("epf-debt", 4, "PPF and EPF balances count toward your debt allocation even though they don't feel like 'debt' funds."));
        if (al.get("equity") > 0) considerations.add(c("index", 4, "For the " + al.get("equity")
                + "% equity slice, a low-cost index fund covers most of what you need — fewer funds, less overlap."));
        if (al.get("gold") > 0) considerations.add(c("gold", 3, "Hold the " + al.get("gold")
                + "% gold slice through a gold ETF or gold fund rather than jewellery — no making charges, and easy to rebalance."));
        considerations.add(c("cash-sep", 3, "The " + al.get("cash")
                + "% cash slice is for rebalancing and near-term needs — keep your emergency fund separate so it isn't counted twice."));
        if (!drags.isEmpty() && capacity < tolerance) considerations.add(c("revisit", 6, "Re-run the assessment once " + lowerFirst(drags.get(0).label())
                + " improves — capacity is what's holding the band down, and it can change within a year."));
        considerations.add(c("blended", 3, "Blended across the four slices, this mix assumes roughly " + pct(blended)
                + "% a year over the long run — some years well above, some below zero."));

        boolean cautious = band == RiskBand.CONSERVATIVE || band == RiskBand.MODERATE;
        List<Candidate> avoid = new ArrayList<>();
        avoid.add(c("chase", 5, "Chasing last year's best-performing fund into an allocation that no longer matches this band.",
                "Switching funds based on last year's rankings — top performers rarely stay on top."));
        avoid.add(c("gold-sub", 3, "Treating gold and equity as substitutes for each other — they respond to very different conditions."));
        if (cautious) {
            avoid.add(c("concentrate", 5, "Concentrating the equity portion in a single stock or sector."));
            avoid.add(c("too-safe", 4, "Keeping so much in FDs and savings accounts that, after tax, your money trails " + fmt(INFLATION_PCT) + "% inflation."));
        } else {
            avoid.add(c("drift", 5, "Letting the equity share drift upward unchecked after a strong run, without rebalancing back."));
        }
        if (band == RiskBand.GROWTH || band == RiskBand.AGGRESSIVE) avoid.add(c("fno", 4,
                "Using F&O or margin trading to 'boost' the equity slice — SEBI's own study found about 9 in 10 individual F&O traders lose money."));
        if (profile != ProfileType.RETIREE) avoid.add(c("stop-sip", 4, "Stopping SIPs during a market fall — that's precisely when each instalment buys the most units."));
        avoid.add(c("ef-dip", 3, "Dipping into the emergency fund to 'buy the dip' — it leaves you exposed right when markets are shakiest."));
        avoid.add(c("guaranteed", 3, "Anything promising 'guaranteed' high returns — well above FD rates with no risk is the classic sign of a scam."));
        avoid.add(c("ulip", 3, "Mixing insurance and investment (ULIPs, endowment plans) — they usually do both jobs worse than term cover plus an index fund."));
        avoid.add(c("daily", 2, "Checking the portfolio every day — short-term noise makes the long-term plan much harder to stick to."));

        return new RiskPools(angles, considerations, avoid);
    }

    public Chosen chooseRisk(RiskPools pools, DraftMemory mem) {
        Angle angle = chooseAngle(pools.angles(), mem);
        List<String> ids = new ArrayList<>();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("rationale", angle.build().get());
        content.put("considerations", chooseFrom(pools.considerations(), 4, mem, ids));
        content.put("avoid", chooseFrom(pools.avoid(), 3, mem, ids));
        return new Chosen(content, ids, angle.id());
    }

    // ================================================================= choosing

    /**
     * Picks n candidates. Draft 1 is purely by priority; later drafts
     * penalise anything already shown and add randomness, so each regenerate
     * brings in points the user hasn't read yet. {@code must} points always
     * make it in.
     */
    static List<String> chooseFrom(List<Candidate> cands, int n, DraftMemory mem, List<String> idsOut) {
        record Scored(Candidate c, double s) {}
        List<Scored> scored = new ArrayList<>();
        for (Candidate c : cands) {
            int seen = mem.seenPoints.getOrDefault(c.id(), 0);
            double s = !mem.fresh ? c.priority() : c.must() ? 100 + c.priority() : c.priority() - seen * 5 + mem.random.nextDouble() * 6;
            scored.add(new Scored(c, s));
        }
        scored.sort((a, b) -> Double.compare(b.s(), a.s()));
        List<String> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (Scored x : scored) {
            if (out.size() == n) break;
            if (!used.add(x.c().id())) continue; // the same id can appear twice (e.g. two health variants)
            idsOut.add(x.c().id());
            out.add(phrase(x.c(), mem));
        }
        return out;
    }

    private static String phrase(Candidate c, DraftMemory mem) {
        List<String> variants = c.text();
        int i = 0;
        if (variants.size() > 1) {
            Integer last = mem.lastVariant.get(c.id());
            int seen = mem.seenPoints.getOrDefault(c.id(), 0);
            // A point shown before comes back in a different phrasing.
            i = seen > 0 ? seen % variants.size() : mem.fresh ? mem.random.nextInt(variants.size()) : 0;
            if (last != null && i == last) i = (i + 1) % variants.size();
        }
        mem.lastVariant.put(c.id(), i);
        return variants.get(i);
    }

    /** The default framing on draft 1, then the least-used one that applies. */
    static Angle chooseAngle(List<Angle> angles, DraftMemory mem) {
        List<Angle> usable = angles.stream().filter(a -> a.build() != null).toList();
        Angle chosen = usable.getFirst();
        if (mem.fresh) {
            int least = usable.stream().mapToInt(a -> mem.seenAngles.getOrDefault(a.id(), 0)).min().orElse(0);
            List<Angle> leastUsed = usable.stream().filter(a -> mem.seenAngles.getOrDefault(a.id(), 0) == least).toList();
            chosen = leastUsed.get(mem.random.nextInt(leastUsed.size()));
        }
        mem.seenAngles.merge(chosen.id(), 1, Integer::sum);
        return chosen;
    }

    // ================================================================= helpers

    private static Candidate c(String id, double p, String... text) {
        return new Candidate(id, p, false, List.of(text));
    }

    private static Candidate must(String id, double p, String... text) {
        return new Candidate(id, p, true, List.of(text));
    }

    static String pick(Random rnd, String... options) {
        return options[rnd.nextInt(options.length)];
    }

    public static String listJoin(List<String> items) {
        if (items.size() <= 1) return String.join("", items);
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.getLast();
    }

    public static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String fmt(double d) {
        return pct(d);
    }

    private static String oneDecimal(double d) {
        return pct(d);
    }

    /** Only used to keep the CATEGORY_TIPS keys honest against the enum at startup. */
    static {
        for (String k : CATEGORY_TIPS.keySet()) QuestionCategory.valueOf(k);
    }
}
