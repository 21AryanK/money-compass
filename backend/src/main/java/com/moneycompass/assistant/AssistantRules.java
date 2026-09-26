package com.moneycompass.assistant;

import com.moneycompass.domain.ProfileType;
import com.moneycompass.domain.QuestionCategory;
import com.moneycompass.domain.RiskBand;
import com.moneycompass.engine.FinancialSnapshot;
import com.moneycompass.engine.SnapshotCalculator.CapacityFactor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.moneycompass.engine.Money.amount;
import static com.moneycompass.engine.Money.inr;
import static com.moneycompass.engine.Money.roundTo;
import static com.moneycompass.engine.PlanMath.*;
import static com.moneycompass.engine.SnapshotCalculator.*;
import static com.moneycompass.narrative.NarrativeTemplates.capitalize;
import static com.moneycompass.narrative.NarrativeTemplates.label;
import static com.moneycompass.narrative.NarrativeTemplates.listJoin;
import static com.moneycompass.narrative.NarrativeTemplates.lowerFirst;

/**
 * The assistant's deterministic half: works out what a question is about and
 * answers it from the user's real numbers, with the same maths as every other
 * screen. {@link AssistantService} gives this answer to the model as the
 * grounded draft to reply from — so a what-if's figures are computed here,
 * not by the model — and returns it as-is when no model is reachable.
 *
 * <p>Order matters: out-of-scope requests and what-ifs are checked before the
 * broad topic keywords.
 */
@Component
public class AssistantRules {

    /**
     * @param topic    what the question was matched to, e.g. "what-if", "debt"
     * @param text     the grounded answer
     * @param chips    suggested follow-up questions
     * @param declined true for requests the assistant won't answer (stock tips)
     */
    public record RuleAnswer(String topic, String text, List<String> chips, boolean declined) {}

    private record Term(Pattern re, String text) {}

    private static final Pattern WHAT_IF_YEARS = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:years?|yrs?|y)\\b");
    private static final Pattern WHAT_IF_AMOUNT = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)\\s*(k|thousand|lakhs?|lac|l)?\\b");

    /** Opening suggestions, tailored to the screen the user is on. */
    public List<String> starterChips(AssistantContext c, String screen) {
        double amt = c.flow() != null ? Math.max(1000, roundTo(c.flow().toInvest() * 2, 1000)) : 5000;
        List<String> chips = new ArrayList<>(switch (screen == null ? "score" : screen) {
            case "risk" -> List.of("Why am I in the " + c.activeBand().name().toLowerCase() + " band?",
                    "What happens in a market crash?", "Should I take more risk?");
            case "invest" -> List.of("Where should my monthly investment go?",
                    "What if I invest " + inr(amt) + " a month for 15 years?", "How do I stay on track?");
            default -> List.of("What should I do first?", "Why is my score " + c.total() + "?",
                    "How big should my emergency fund be?");
        });
        FinancialSnapshot s = c.snapshot();
        if (Boolean.TRUE.equals(s.highInterestDebt())) chips.add("How do I clear my debt?");
        else if (c.profile() == ProfileType.PROFESSIONAL) chips.add("How can I save tax?");
        else if (c.profile() == ProfileType.RETIREE) chips.add("Will my money last?");
        else chips.add("What if I invest " + inr(amt) + " a month for 15 years?");
        return chips;
    }

    /** The opening message: what the assistant has read, and an invitation. */
    public String greeting(AssistantContext c) {
        FinancialSnapshot s = c.snapshot();
        return "Hi — I've read through your " + c.answered() + " answers. You scored " + c.total()
                + "/100, and we're planning around " + (c.activeBand() == RiskBand.AGGRESSIVE ? "an " : "a ")
                + c.activeBand().name().toLowerCase() + " risk profile"
                + (s.incomeKnown() ? " on an income of about " + amount(s.income()) + " a month" : "")
                + ". Ask me anything about your results — why a number came out the way it did, what to do first, or what-ifs like "
                + "“what if I invest ₹5,000 a month for 15 years?”";
    }

    public RuleAnswer answer(String raw, AssistantContext c) {
        String q = raw.toLowerCase(Locale.ROOT).trim();
        FinancialSnapshot s = c.snapshot();
        RiskBand band = c.activeBand();
        String bandL = band.name().toLowerCase();
        Map<String, Integer> al = c.allocation();
        var flow = c.flow();
        boolean incomeKnown = s.incomeKnown();
        List<String> ranked = c.breakdown().entrySet().stream().filter(e -> e.getValue() != null)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue())).map(Map.Entry::getKey).toList();
        String bestCat = ranked.getFirst(), worstCat = ranked.getLast();
        String worst = label(worstCat).toLowerCase();
        double blended = round1(blendedReturn(al));
        List<Term> glossary = glossary();

        if (has(q, "^(hi|hello|hey|namaste|hii+)\\b")) {
            return new RuleAnswer("greeting", "Hello! I'm working from your own answers, so ask me anything about your score, your "
                    + bandL + " risk profile or your plan.", starterChips(c, "score"), false);
        }
        if (has(q, "thank|thx|great|helpful|awesome")) {
            return new RuleAnswer("thanks", "Glad that helped. If you do just one thing this week, make it: "
                    + lowerFirst(c.priorities().items().getFirst().title()) + ".",
                    List.of("What should I do first?", "What happens in a market crash?"), false);
        }
        if (has(q, "\\b(which|what|best|good|recommend|suggest)\\b.*\\b(stock|share|crypto|coin|bitcoin|ipo)s?\\b|\\bbitcoin\\b|\\bcrypto\\b|\\bf&o\\b|\\boptions trading\\b|\\bintraday\\b")) {
            return new RuleAnswer("stock-tips", "I can't point you to specific stocks, coins or trades — this is an educational tool, not an adviser, "
                    + "and picks like that would need a SEBI-registered adviser who knows your full situation.\n\nWhat I can do: your " + bandL
                    + " profile suggests about " + al.get("equity") + "% in equity, and for most people a low-cost index fund covers that without any stock-picking at all."
                    + (has(q, "f&o|options|intraday") ? " On F&O and intraday trading specifically: SEBI's own study found about 9 in 10 individual F&O traders lost money." : ""),
                    List.of("What is an index fund?", "Where should my monthly investment go?"), true);
        }

        // ---- what-ifs: the maths is done here, never by the model
        double[] wi = parseWhatIf(q);
        if (wi[0] > 0 && has(q, "invest|sip|put|save|saving|grow|become|what if|how much will|worth")) {
            long amt = Math.round(wi[0]);
            int years = (int) Math.min(Math.max(wi[1] > 0 ? wi[1] : c.planYears(), 1), 50);
            boolean lump = has(q, "lump|one[- ]time|once|lakhs? (now|today)") && !has(q, "month|monthly|sip|every");
            double fvBand = lump ? amt * Math.pow(1 + blended / 100, years) : sipFutureValue(amt, blended, years);
            double fvEq = lump ? amt * Math.pow(1 + RATE_MF / 100, years) : sipFutureValue(amt, RATE_MF, years);
            double put = lump ? amt : amt * 12.0 * years;
            double real = fvBand / Math.pow(1 + INFLATION_PCT / 100, years);
            List<String> lines = new ArrayList<>(List.of(
                    "Here's the rough maths for " + inr(amt) + (lump ? " invested once" : " a month") + " over " + years + " year" + (years > 1 ? "s" : "") + ":",
                    "",
                    "• You'd put in " + amount(put),
                    "• In your " + bandL + " mix (~" + pct(blended) + "% a year assumed), it could grow to about " + amount(fvBand),
                    "• All in an equity index fund (~" + pct(RATE_MF) + "%), about " + amount(fvEq) + " — with much bigger swings along the way",
                    "• In today's money, after " + pct(INFLATION_PCT) + "% inflation, the " + bandL + " figure is worth about " + amount(real)));
            if (!lump && years >= 5) lines.add("• Raise it 10% every year and it becomes about " + amount(stepUpFutureValue(amt, blended, years, 10)));
            List<String> notes = new ArrayList<>();
            if (!lump && flow != null && amt > flow.budget() * 1.2) notes.add("That's more than the roughly " + inr(roundTo(flow.budget(), 100))
                    + " a month your answers suggest you set aside today, so it would mean trimming spending somewhere.");
            if (Boolean.TRUE.equals(s.highInterestDebt())) notes.add("One caveat: clear the high-interest debt first — at ~" + pct(HIGH_INTEREST_PCT)
                    + "% it outruns every one of these returns.");
            else if (s.efGap() != null && s.efGap() > 0) notes.add("Worth doing only once your emergency fund is in place, so a surprise bill never forces you to sell.");
            if (!notes.isEmpty()) {
                lines.add("");
                lines.add(String.join(" ", notes));
            }
            lines.add("");
            lines.add("These are illustrative long-run averages, not promises — real returns vary and some years are negative.");
            return new RuleAnswer("what-if", String.join("\n", lines), List.of("What happens in a market crash?",
                    "What if I invest " + inr(amt * 2) + (lump ? " once" : " a month") + " for " + years + " years?", "What is a SIP?"), false);
        }

        if (has(q, "what is|what's|whats|what are|explain|meaning|define|how does")) {
            for (Term t : glossary) {
                if (t.re().matcher(q).find()) return new RuleAnswer("glossary", t.text(), List.of("How does this apply to me?", "What should I do first?"), false);
            }
        }

        if (has(q, "first|priorit|start|begin|what should i do|next step|where do i|apply to me|one thing")) {
            var pp = c.priorities();
            StringBuilder sb = new StringBuilder(pp.intro()).append("\n\n");
            for (int i = 0; i < pp.items().size(); i++) {
                var it = pp.items().get(i);
                sb.append(i + 1).append(". ").append(it.title()).append(it.amount().isEmpty() ? "" : " — " + it.amount()).append(". ").append(it.detail()).append('\n');
            }
            sb.append("\nThe order matters more than the exact amounts: each step protects the one after it.");
            return new RuleAnswer("first-steps", sb.toString(), List.of(Boolean.TRUE.equals(s.highInterestDebt()) ? "How do I clear my debt?" : "How big should my emergency fund be?",
                    "Where should my monthly investment go?", "Why is my score " + c.total() + "?"), false);
        }

        if (has(q, "score|literacy|\\b\\d{1,3}\\s*/\\s*100\\b|why.*(low|high|only)|out of 100")) {
            List<String> misses = s.missedTopics().stream().limit(3).map(FinancialSnapshot.MissedTopic::topic).toList();
            int bestPct = c.breakdown().get(bestCat), worstPct = c.breakdown().get(worstCat);
            String lines = ranked.stream().map(cat -> "• " + label(cat) + " — " + c.breakdown().get(cat) + "%").collect(Collectors.joining("\n"));
            return new RuleAnswer("score", "Your " + c.total() + "/100 is the weighted average of " + ranked.size() + " category scores. From highest to lowest:\n\n"
                    + lines + "\n\n" + capitalize(worst) + " is pulling the total down the most"
                    + (misses.isEmpty() ? "" : ", and the questions that cost you points were about " + listJoin(misses))
                    + ". The score itself is calculated by fixed rules, not by me — I'm only explaining it. Lifting " + worst + " to around " + bestPct
                    + "% would raise the total by roughly " + Math.max(1, Math.round((bestPct - worstPct) / (double) ranked.size())) + " points.",
                    List.of("How can I improve " + worst + "?", "What should I do first?"), false);
        }

        if (has(q, "improve|get better|raise|increase my")) {
            String cat = Arrays.stream(QuestionCategory.values()).map(Enum::name)
                    .filter(k -> q.contains(k.toLowerCase())).findFirst().orElse(worstCat);
            Integer v = c.breakdown().get(cat);
            List<String> tips = TIPS.getOrDefault(cat, List.of());
            return new RuleAnswer("improve", "For " + label(cat).toLowerCase() + " (you're at " + (v == null ? "n/a" : v + "%")
                    + "), two things tend to move it fastest:\n\n• " + String.join("\n• ", tips) + "\n\nRetake the assessment in a month or two to see the change.",
                    List.of("What should I do first?", "Why is my score " + c.total() + "?"), false);
        }

        if (has(q, "emergency|cushion|rainy|job loss|lose my job")) {
            if (!incomeKnown) return new RuleAnswer("emergency", "The rule of thumb for you is " + s.efTargetMonths()
                    + " months of essential spending, kept somewhere you can withdraw within a day — a liquid fund or sweep-in FD. You didn't share your income, so I can't put a rupee figure on it; retake the assessment with a rough number and I can.",
                    List.of("What is a liquid fund?", "What should I do first?"), false);
            String have = s.efMonths() == null ? "You weren't sure what you have, so I've assumed nothing yet."
                    : "You have roughly " + pct(s.efMonths()) + " months (" + amount(s.efHave()) + ")"
                    + (s.efGap() > 0 ? ", so the gap is about " + amount(s.efGap()) + "." : " — you're covered.");
            return new RuleAnswer("emergency", "For you, " + s.efTargetMonths() + " months of spending is about " + amount(s.efTarget()) + ". " + have
                    + (flow != null && flow.toEf() > 0 ? "\n\nAt " + inr(flow.toEf()) + " a month you'd close it in about " + flow.efMonthsToFill() + " month"
                    + (flow.efMonthsToFill() == 1 ? "" : "s") + "." : "")
                    + "\n\nKeep it in a liquid fund or sweep-in FD — not in equity, and not mixed with your investments.",
                    List.of("What is a liquid fund?", "Where should my monthly investment go?"), false);
        }

        if (has(q, "debt|loan|credit card|\\bemi\\b|borrow|owe")) {
            if (Boolean.TRUE.equals(s.highInterestDebt())) return new RuleAnswer("debt", "Clearing the high-interest debt is the best return available to you — about "
                    + pct(HIGH_INTEREST_PCT) + "% a year, guaranteed.\n\n• List every balance with its rate; pay minimums on all and everything extra on the most expensive\n"
                    + (flow != null && flow.toDebt() > 0 ? "• Put about " + inr(flow.toDebt()) + " a month at it — half of what you set aside\n" : "")
                    + "• Ask your bank about moving it to a cheaper personal loan\n• Stop adding to it: pay card bills in full from now on\n\nOnce it's gone, that money moves to your emergency fund and then to investing.",
                    List.of("How big should my emergency fund be?", "What should I do first?"), false);
            return new RuleAnswer("debt", (Boolean.FALSE.equals(s.highInterestDebt()) ? "Good news — you don't carry high-interest debt, which is one of the biggest things in your favour. " : "")
                    + ("C".equals(s.emiBand()) || "D".equals(s.emiBand()) ? "Your EMIs take " + ("D".equals(s.emiBand()) ? "over 40%" : "20–40%")
                    + " of your income, though, so I'd avoid any new loan until that's lower. " : "")
                    + "The general rule: borrow only for things that grow in value or earn you more (a home, education), keep total EMIs under about 30–40% of take-home, and pay credit cards in full every month.",
                    List.of("What should I do first?", "Where should my monthly investment go?"), false);
        }

        if (has(q, "crash|fall|drop|downturn|recession|lose money|volatil")) {
            long drop = Math.round(al.get("equity") * 0.3);
            double basis = c.planMonthly() > 0 ? c.planMonthly() * 12 * 5 : 100000;
            String reaction = s.dropScale() != null && s.dropScale() <= 2
                    ? "You told me a 20% fall would tempt you to sell. That's the moment that turns a paper loss into a real one — so decide now, in writing, what you'll do instead."
                    : s.dropScale() != null && s.dropScale() >= 4
                    ? "You said you'd hold through a 20% fall — that's exactly the behaviour this band relies on."
                    : "The debt, gold and cash slices are there so you're never forced to sell equity at the bottom.";
            return new RuleAnswer("crash", "Suppose equities fell 30% — a bad but not unusual year. With " + al.get("equity") + "% in equity, your " + bandL
                    + " portfolio would fall about " + drop + "%. On " + amount(basis) + (c.planMonthly() > 0 ? " (five years of your planned monthly amount)" : "")
                    + ", that's roughly " + amount(basis * drop / 100) + " on paper.\n\n" + reaction
                    + " Historically, broad Indian equity indices have recovered from every major fall, though it has sometimes taken a few years.",
                    List.of("Should I take more risk?", "What is rebalancing?"), false);
        }

        if (has(q, "more risk|less risk|aggressive|conservative|should i (take|change)|higher band|lower band")) {
            int bi = band.ordinal();
            RiskBand up = bi < RiskBand.values().length - 1 ? RiskBand.values()[bi + 1] : null;
            RiskBand capBand = RiskBand.values()[bandIndex(c.capacity())];
            return new RuleAnswer("more-risk", (c.capacity() < c.tolerance()
                    ? "Your answers say you'd tolerate more risk (" + c.tolerance() + "/100) than your finances can currently absorb (" + c.capacity() + "/100) — and capacity always caps the band. "
                    : "Your capacity (" + c.capacity() + ") is at least as high as your tolerance (" + c.tolerance() + "), so the band reflects how you'd actually feel in a fall. ")
                    + (up != null ? "Moving up to " + up.name().toLowerCase() + " would mean " + ALLOCATIONS.get(up).get("equity") + "% equity instead of "
                    + al.get("equity") + "%, and a bigger dip in a bad year." : "You're already in the highest band.")
                    + (band.ordinal() > capBand.ordinal() ? "\n\nNote: you've picked a band above what your capacity supports." : "")
                    + "\n\nThe honest answer: take more risk only once the basics are in place — no high-interest debt and a full emergency fund. Those are what raise your capacity.",
                    List.of("What happens in a market crash?", "Why am I in the " + bandL + " band?"), false);
        }

        if (has(q, "risk|band|allocation|equity|debt fund|gold|moderate|balanced|growth")) {
            List<CapacityFactor> drags = c.factors().stream().skip(1).filter(f -> f.delta() < 0)
                    .sorted(Comparator.comparingInt(CapacityFactor::delta)).limit(2).toList();
            return new RuleAnswer("band", "Two numbers decide your band: tolerance (" + c.tolerance() + "/100, how you'd react to losses) and capacity ("
                    + c.capacity() + "/100, whether your finances could absorb them). The band follows the lower one, which puts you in "
                    + capitalize(c.calculatedBand().name().toLowerCase()) + (band != c.calculatedBand() ? " — though you've chosen to plan around " + bandL : "") + ".\n\n"
                    + (drags.isEmpty() ? "" : "What's holding capacity back most: "
                    + listJoin(drags.stream().map(f -> lowerFirst(f.label()) + " (" + f.delta() + ")").toList()) + ".\n\n")
                    + "That gives a split of " + al.get("equity") + "% equity, " + al.get("debt") + "% debt, " + al.get("gold") + "% gold and "
                    + al.get("cash") + "% cash — about " + pct(blended) + "% a year on long-run assumptions.",
                    List.of("Should I take more risk?", "What happens in a market crash?"), false);
        }

        if (has(q, "tax|80c|80d|regime|deduction|elss")) {
            if (c.profile() == ProfileType.STUDENT) return new RuleAnswer("tax", "As a student you're unlikely to owe income tax yet, so don't let tax drive your choices. "
                    + "When you start earning, compare the new and old regimes — under the new one, salaried income up to ₹12.75 lakh pays no tax.",
                    List.of("What is an ELSS?", "What should I do first?"), false);
            if (c.profile() == ProfileType.RETIREE) return new RuleAnswer("tax", "A few things usually matter most in retirement: submit Form 15H to each bank every April if your total income is below the taxable limit, "
                    + "so TDS isn't cut from FD interest; and the senior-citizen health insurance deduction under 80D is higher (up to ₹50,000) if you're on the old regime.",
                    List.of("Will my money last?", "What should I do first?"), false);
            return new RuleAnswer("tax", "First decide your regime. Under the new regime, salaried income up to ₹12.75 lakh pays no tax"
                    + (incomeKnown ? " — at about " + amount(s.income() * 12) + " a year, " + (s.income() * 12 <= 1275000 ? "you'd likely owe nothing on salary" : "you're above that line") : "")
                    + ". The old regime only wins if your deductions are large:\n\n• 80C — up to ₹1.5 lakh (EPF, PPF, ELSS, life premiums)\n• 80CCD(1B) — another ₹50,000 in NPS\n• 80D — health insurance premiums\n\n"
                    + "Run both through your employer's or the tax department's calculator before the declaration deadline.",
                    List.of("What is an ELSS?", "What is NPS?"), false);
        }

        if (has(q, "retire|pension|corpus|last|run out|withdraw")) {
            if (c.profile() == ProfileType.RETIREE) return new RuleAnswer("retirement", (s.runwayYears() != null ? "You estimated your corpus lasts about " + Math.round(s.runwayYears()) + " years. " : "")
                    + "A safer target is 25–30 years. Three levers help: keep 2 years of spending" + (s.spend() != null ? " (about " + amount(s.spend() * 24) + ")" : "")
                    + " in debt and cash so you never sell equity in a fall; keep a small equity share (your band holds " + al.get("equity") + "%) to outpace "
                    + pct(INFLATION_PCT) + "% inflation; and withdraw a steady amount through an SWP rather than ad hoc.",
                    List.of("What happens in a market crash?", "How can I save tax?"), false);
            if (s.spend() == null) return new RuleAnswer("retirement", "A common target is 25× your annual spending. Share a rough income in the assessment and I can put a rupee figure and a monthly SIP on it.",
                    List.of("What is NPS?", "What should I do first?"), false);
            double corpus = s.spend() * 12 * 25;
            double realPct = ((1 + RATE_MF / 100) / (1 + INFLATION_PCT / 100) - 1) * 100;
            return new RuleAnswer("retirement", "A common target is 25× annual spending — about " + amount(corpus) + " in today's money for you."
                    + (s.yearsToRetire() != null && s.yearsToRetire() > 0 ? " With about " + s.yearsToRetire() + " years to " + RETIREMENT_AGE + ", that's roughly "
                    + amount(corpus / sipFutureValue(1, realPct, s.yearsToRetire())) + " a month from scratch, raised with inflation each year." : "")
                    + "\n\nYour EPF and any PPF/NPS count towards it, so the real gap is smaller. Starting earlier is the biggest lever: every 5 years of delay roughly doubles the monthly amount needed.",
                    List.of("What is NPS?", "What if I invest ₹5,000 a month for " + (s.yearsToRetire() != null && s.yearsToRetire() > 0 ? s.yearsToRetire() : 20) + " years?"), false);
        }

        if (has(q, "insur|term|health|medical|hospital|cover")) {
            List<String> bits = new ArrayList<>();
            if ("A".equals(s.healthCover()) || "?".equals(s.healthCover())) bits.add("You don't have health cover of your own — that's the most urgent gap. A ₹5–10 lakh base policy plus a super top-up is a common, affordable structure.");
            else if ("B".equals(s.healthCover())) bits.add("Your health cover comes only through your employer or college, and it ends when you leave. A small personal policy bought now stays with you and is cheaper while you're young.");
            else if (s.healthCover() != null) bits.add("Your health cover looks sensible.");
            if ("B".equals(s.dependents()) || "C".equals(s.dependents())) bits.add("D".equals(s.termCover())
                    ? "Your term cover looks adequate for the people who depend on you."
                    : "People depend on your income, so a pure term plan of about " + (incomeKnown ? amount(s.income() * 120) : "10× your annual income")
                    + " is the priority. Avoid endowment and money-back plans — they give far less cover for the same premium.");
            if (bits.isEmpty()) bits.add("The two covers that matter: health insurance for everyone, and term life insurance once anyone depends on your income. Keep insurance and investing separate — combined products usually do both jobs worse.");
            return new RuleAnswer("insurance", String.join("\n\n", bits), List.of("What is term insurance?", "What should I do first?"), false);
        }

        if (has(q, "invest|sip|mutual fund|where.*(put|money)|portfolio|index")) {
            double monthly = c.planMonthly();
            return new RuleAnswer("where-to-invest", "Based on your " + bandL + " profile, " + inr(monthly) + " a month would split roughly like this:\n\n"
                    + "• Equity " + al.get("equity") + "% — " + inr(roundTo(monthly * al.get("equity") / 100, 100)) + " (a Nifty 50 or Nifty 500 index fund is enough)\n"
                    + "• Debt " + al.get("debt") + "% — " + inr(roundTo(monthly * al.get("debt") / 100, 100)) + " (PPF, EPF or a debt fund)\n"
                    + "• Gold " + al.get("gold") + "% — " + inr(roundTo(monthly * al.get("gold") / 100, 100)) + " (a gold ETF or fund)\n"
                    + "• Cash " + al.get("cash") + "% — " + inr(roundTo(monthly * al.get("cash") / 100, 100)) + "\n\n"
                    + "Over " + c.planYears() + " years that could grow to about " + amount(sipFutureValue(monthly, blended, c.planYears())) + " at ~" + pct(blended) + "% a year."
                    + (Boolean.TRUE.equals(s.highInterestDebt()) ? " But clear the high-interest debt before any of this." : ""),
                    List.of("What if I invest " + inr(monthly * 2) + " a month for 15 years?", "What is an index fund?", "What happens in a market crash?"), false);
        }

        if (has(q, "sav|budget|spend|expense|50/30|tracking")) {
            return new RuleAnswer("saving", (incomeKnown ? "You save about " + Math.round(s.savingsRate()) + "% (" + amount(s.saving()) + " a month) of " + amount(s.income()) + ". " : "")
                    + (s.savingsRate() < 20 ? "Getting to 20% is the usual goal. " : "That's at or above the usual 20% goal. ")
                    + "Two habits do most of the work:\n\n• Automate it — a transfer or SIP on the day money arrives"
                    + ("C".equals(s.autoSave()) || "D".equals(s.autoSave()) ? " (you already do this)" : "")
                    + "\n• Track for one month — log every spend over ₹200" + ("D".equals(s.tracking()) ? " (you already track in detail)" : "")
                    + (incomeKnown ? "\n\nA 50/30/20 check on your income: " + amount(s.income() * 0.5) + " needs, " + amount(s.income() * 0.3) + " wants, "
                    + amount(s.income() * 0.2) + " saving." : ""),
                    List.of("What should I do first?", "Where should my monthly investment go?"), false);
        }

        if (has(q, "on track|stay|stick|discipline|review|monitor")) {
            return new RuleAnswer("on-track", "Three habits keep a plan like yours on track:\n\n• Automate the monthly amount so it happens without a decision\n• Rebalance once a year back to "
                    + al.get("equity") + "/" + al.get("debt") + "/" + al.get("gold") + "/" + al.get("cash") + "\n• Retake this assessment every 6–12 months — your capacity ("
                    + c.capacity() + "/100) will change as your debt and emergency fund do\n\nAnd avoid checking the portfolio daily; it makes the plan harder to stick to.",
                    List.of("What is rebalancing?", "Should I take more risk?"), false);
        }

        return new RuleAnswer("other", "I don't have a ready answer for that from your results. I can explain your score (" + c.total() + "/100), your " + bandL
                + " risk profile, your emergency fund, debt, tax or retirement — or run a what-if like “what if I invest ₹5,000 a month for 10 years?”",
                starterChips(c, "score"), false);
    }

    /** Reads a rupee amount (5000, 5,000, 5k, 1.5 lakh) and a number of years out of free text: {amount, years}. */
    static double[] parseWhatIf(String q) {
        double years = 0;
        Matcher ym = WHAT_IF_YEARS.matcher(q);
        String rest = q;
        if (ym.find()) {
            years = Math.round(Double.parseDouble(ym.group(1)));
            rest = q.substring(0, ym.start()) + " " + q.substring(ym.end());
        }
        Matcher am = WHAT_IF_AMOUNT.matcher(rest);
        while (am.find()) {
            double n = Double.parseDouble(am.group(1).replace(",", ""));
            String unit = am.group(2);
            if ("k".equals(unit) || "thousand".equals(unit)) n *= 1000;
            else if (unit != null) n *= 100000;
            if (n >= 100) return new double[]{n, years};
        }
        return new double[]{0, years};
    }

    private static boolean has(String q, String regex) {
        return Pattern.compile(regex).matcher(q).find();
    }

    private static final Map<String, List<String>> TIPS = Map.of(
            "BUDGETING", List.of("Try a 'spending review Sunday': ten minutes once a week comparing what went out against what you planned.",
                    "Split your bank balance on payday — one account for fixed bills, one for everything else — so overspending shows up before the month ends."),
            "SAVING", List.of("Move money out on the day it arrives rather than at month-end — what's left over is almost always less than planned.",
                    "Give each savings pot a name and a number; named goals get funded far more reliably than a general 'savings'."),
            "DEBT", List.of("List every balance with its interest rate and pay the highest rate first while making minimums on the rest.",
                    "Pay any credit card bill in full, not the 'minimum due' — the minimum keeps the whole balance accruing interest at 3%+ a month."),
            "INVESTING", List.of("A single low-cost Nifty 50 or Nifty 500 index fund is a complete first step — no stock-picking needed.",
                    "Before investing in anything, check it's registered with SEBI (or RBI, for deposits) — it takes two minutes and rules out most scams."),
            "COMPOUNDING", List.of("Use the Rule of 72: divide 72 by the annual return to get the years it takes money to double.",
                    "When your income rises, send at least half of each raise straight to savings before you get used to spending it."),
            "RISK", List.of("Decide now what you'll do in a 20–30% fall and write it down — a plan made calmly beats a decision made in a panic.",
                    "Match money to time: anything you need within 3 years shouldn't be in equity, however good the market looks."),
            "RETIREMENT", List.of("Find out your current EPF and PPF balances — most people underestimate what's already building.",
                    "Treat retirement saving as a fixed bill, not a leftover: a set amount on a set date, raised a little every year."));

    private static List<Term> glossary() {
        return List.of(
                new Term(Pattern.compile("\\bsip\\b|systematic investment"), "A SIP (systematic investment plan) invests a fixed amount into a mutual fund every month, automatically. Because the amount is fixed, you buy more units when prices are low and fewer when they're high, and you never have to time the market."),
                new Term(Pattern.compile("index fund"), "An index fund simply buys every company in an index like the Nifty 50, in the same proportions. There's no fund manager picking stocks, so costs are very low — and over long periods most actively managed funds fail to beat their index after fees."),
                new Term(Pattern.compile("\\belss\\b"), "ELSS (equity-linked savings scheme) is an equity mutual fund that qualifies for the Section 80C deduction under the old tax regime. It has a 3-year lock-in — the shortest of any 80C option — but its value moves with the stock market."),
                new Term(Pattern.compile("\\bppf\\b"), "PPF (Public Provident Fund) is a government-backed savings scheme with a 15-year lock-in, tax-free interest and up to ₹1.5 lakh a year of deposits. It's a solid anchor for the debt side of a long-term portfolio."),
                new Term(Pattern.compile("\\bepf\\b|provident fund"), "EPF (Employees' Provident Fund) is the retirement savings your employer and you contribute to from your salary each month. It counts towards the debt part of your allocation, and it's worth transferring — not withdrawing — when you change jobs."),
                new Term(Pattern.compile("\\bnps\\b|national pension"), "NPS (National Pension System) is a low-cost, market-linked retirement account. Under the old regime, 80CCD(1B) gives an extra ₹50,000 deduction on top of 80C; most of it is locked until 60."),
                new Term(Pattern.compile("rule of 72"), "The Rule of 72 is a quick way to see how long money takes to double: divide 72 by the annual return. At " + pct(RATE_MF) + "% a year that's about " + pct(72 / RATE_MF) + " years; at " + pct(RATE_FD) + "% it's about " + Math.round(72 / RATE_FD) + "."),
                new Term(Pattern.compile("compound"), "Compounding means your returns start earning returns of their own. It's slow at first and dramatic later — which is why the years you stay invested matter more than the exact amount you start with."),
                new Term(Pattern.compile("liquid fund|sweep"), "A liquid fund invests in very short-term debt and can usually be withdrawn within a day. A sweep-in FD links a fixed deposit to your savings account. Both earn more than a plain savings account while staying accessible — ideal for an emergency fund."),
                new Term(Pattern.compile("term (plan|insurance|cover)"), "A term plan is pure life insurance: if you die during the term, your family gets the cover amount; if you don't, nothing is paid back. That's exactly why it's so cheap — and why about 10× your annual income is affordable for most healthy people."),
                new Term(Pattern.compile("rebalanc"), "Rebalancing means moving money back to your target split — say, from equity to debt after a strong year. It keeps your risk where you chose it, and quietly makes you sell high and buy low."),
                new Term(Pattern.compile("inflation"), "Inflation is the rise in prices over time — assumed at " + pct(INFLATION_PCT) + "% a year here. It means ₹1 lakh today buys only about " + amount(100000 / Math.pow(1 + INFLATION_PCT / 100, 10)) + " worth of things in 10 years, so money needs to grow just to stand still."));
    }

    /** Keeps the tips' keys honest against the enum. */
    static {
        for (String k : TIPS.keySet()) QuestionCategory.valueOf(k);
    }
}
