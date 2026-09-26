package com.moneycompass.plan;

import com.moneycompass.domain.ProfileType;
import com.moneycompass.domain.RiskBand;
import com.moneycompass.engine.FinancialSnapshot;
import com.moneycompass.engine.SnapshotCalculator;
import com.moneycompass.engine.SnapshotCalculator.Waterfall;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.moneycompass.engine.Money.amount;
import static com.moneycompass.engine.Money.inr;
import static com.moneycompass.engine.Money.roundTo;
import static com.moneycompass.engine.PlanMath.*;
import static com.moneycompass.engine.SnapshotCalculator.HIGH_INTEREST_PCT;
import static com.moneycompass.engine.SnapshotCalculator.MIN_SIP;

/**
 * The investment plan — pure maths, deliberately not AI: this is the
 * "deterministic core" side of the app. Takes the active risk band's
 * allocation, routes the equity slice through the vehicle(s) the user picked,
 * breaks each slice into instruments, and projects growth month by month
 * against illustrative long-term returns, after fees and an estimate of tax.
 *
 * <p>Every figure and rule here matches the prototype's planner so the two
 * give identical plans for identical inputs.
 */
@Component
public class InvestmentPlanner {

    public record Part(String key, String label, String what, double rate, String color, double pct, double monthly) {}

    public record MixView(String intro, List<Part> parts, List<String> notes, double total) {}

    public record Item(String key, String label, String color, double monthly, double rate, double fee, String tax,
                       double spread, double projected, double invested, double gain, double netValue,
                       List<Double> values, List<Double> low, List<Double> high, List<Double> investedByYear) {}

    public record YearPoint(int year, double invested, double value, double low, double high) {}

    public record Projection(List<Item> items, double totalInvested, double totalProjected, double totalGain,
                             double blendedRate, List<YearPoint> series, double totalLow, double totalHigh,
                             double totalAfterFeesAndTax, double taxEstimate, int slab, boolean slabAssumed,
                             double stepUp, double realValue) {}

    public record Priority(String title, String detail, String amount) {}

    public record Priorities(String intro, List<Priority> items) {}

    // ---------------------------------------------------------- categories

    private record Category(String label, double rate, String color, String what) {}

    /*
     * EQUITY MUTUAL FUND MIX. SEBI defines fund categories by company size:
     * large cap = the 100 largest listed companies, mid cap = 101st–250th,
     * small cap = 251st onward; flexi cap funds can move across all three.
     * The riskier the band, the more of the fund money goes down the ladder.
     */
    private static final Map<String, Category> MF = Map.of(
            "large", new Category("Large cap / Nifty 50 index fund", 11, "#b3d1f6",
                    "India's 100 biggest companies. The steadiest part of your equity, and an index fund does it at the lowest cost."),
            "flexi", new Category("Flexi cap fund", 11.5, "#86b6ef",
                    "The fund manager can move between large, mid and small companies as conditions change."),
            "mid", new Category("Mid cap fund", 12.5, "#5898e6",
                    "Companies ranked 101–250 by size. More room to grow than large caps, with bigger falls along the way."),
            "small", new Category("Small cap fund", 13.5, "#3a7cd0",
                    "Companies ranked 251 and below. The highest growth potential and the sharpest drops — only for money you won't need for 7+ years."));
    private static final List<String> MF_ORDER = List.of("large", "flexi", "mid", "small");
    private static final Map<RiskBand, int[]> MF_SPLIT = Map.of(
            RiskBand.CONSERVATIVE, new int[]{80, 20, 0, 0},
            RiskBand.MODERATE, new int[]{65, 25, 10, 0},
            RiskBand.BALANCED, new int[]{50, 25, 15, 10},
            RiskBand.GROWTH, new int[]{40, 25, 20, 15},
            RiskBand.AGGRESSIVE, new int[]{30, 25, 25, 20});

    /* DEBT MIX: the shorter the time, the more of it stays in deposits, whose value never dips. */
    private static final Map<String, Category> DEBT = Map.of(
            "fd", new Category("Fixed / recurring deposit (FD / RD)", RATE_FD, "#8fd9bb",
                    "A fixed rate for a fixed term — invested monthly, it's a recurring deposit. Bank deposits are insured by DICGC up to ₹5 lakh per bank."),
            "short", new Category("Short duration debt fund", 6.8, "#3fbf90",
                    "Lends to the government and highly rated companies for about 1–3 years. No lock-in; its value can dip a little when interest rates rise."),
            "corp", new Category("Corporate bond / Banking & PSU debt fund", 7, "#127a57",
                    "Mostly top-rated bonds of companies, banks and public-sector firms. A little more return than a deposit and a little more risk — suits money held 3+ years."));
    private static final List<String> DEBT_ORDER = List.of("fd", "short", "corp");
    /** Banks usually pay people aged 60+ about half a percent more on deposits. */
    private static final double SENIOR_FD_BONUS = 0.5;

    /*
     * GOLD MIX. Gold ETFs / funds take a SIP. Sovereign Gold Bonds can't: no
     * new tranche since February 2024, so they're bought on NSE/BSE, 1 unit =
     * 1 gram, paying 2.5% a year, tax-free at maturity.
     */
    private static final Map<String, Category> GOLD = Map.of(
            "etf", new Category("Gold ETF / gold fund", RATE_GOLD, "#e2b34f",
                    "Tracks the price of gold and takes a small SIP every month. Gains are taxed at 12.5% if held over 12 months, at your slab rate if sold sooner."),
            "sgb", new Category("Sovereign Gold Bonds (bought on NSE / BSE)", 8.75, "#9c6a00",
                    "Government bonds priced in gold, paying 2.5% a year on their original issue price. No new ones since February 2024, so you buy existing bonds on the exchange — 1 unit is 1 gram. Gains are tax-free if held to maturity."));
    /** Below this, the SGB share would take over a year to afford one unit (1 gram). */
    private static final double SGB_MIN_MONTHLY = 1000;

    /* CASH MIX: a small share in savings for instant access, the rest in a liquid fund. */
    private static final Map<String, Category> CASH = Map.of(
            "savings", new Category("Savings account", 3, "#bdb5f3",
                    "Instant access at any hour, but the lowest interest. Interest is taxed every year; the old regime exempts up to ₹10,000 of it (₹50,000 of deposit interest for people 60+)."),
            "liquid", new Category("Liquid fund", 6, "#6f62d6",
                    "Lends for up to 91 days, so its value barely moves. Up to ₹50,000 (or 90% of your balance) can be withdrawn instantly each day, the rest by the next working day. Gains are taxed at your slab rate when you sell."));

    /*
     * DIRECT EQUITY BY SECTOR. Starts from the Nifty 50's own sector mix with
     * financials capped at 25%. All sectors share the direct-equity return
     * assumption: this changes where the money goes, not the projection.
     */
    private static final Map<String, Category> SECTORS = Map.of(
            "fin", new Category("Banks & financial services", RATE_DIRECT, "#1c5cab",
                    "Banks, insurers and lenders — the biggest part of India's market, capped here so one sector can't dominate."),
            "tech", new Category("IT & telecom", RATE_DIRECT, "#3b74c4",
                    "Software services and telecom. Earns much of its revenue abroad, so it often moves differently from the rest."),
            "consumer", new Category("Consumer (FMCG, autos, durables)", RATE_DIRECT, "#14477f",
                    "Everyday goods, vehicles and household brands. FMCG in particular holds up well when the economy slows."),
            "energy", new Category("Energy (oil & gas, power)", RATE_DIRECT, "#5b8fd6",
                    "Oil, gas and power companies. Tied to commodity prices and government policy."),
            "industrials", new Category("Industrials & materials", RATE_DIRECT, "#28579c",
                    "Capital goods, construction, metals and cement — they do best when the economy and infrastructure spending grow."),
            "health", new Category("Healthcare & pharma", RATE_DIRECT, "#7ea7e0",
                    "Drug makers and hospitals. Demand stays steady in downturns, which steadies the rest of the portfolio."));
    private static final List<String> SECTOR_ORDER = List.of("fin", "tech", "consumer", "energy", "industrials", "health");
    private static final int[] SECTOR_BASE = {25, 15, 20, 13, 17, 10};
    /** Leans toward sectors whose earnings hold up in a slowdown. */
    private static final int[] SECTOR_DEFENSIVE = {25, 13, 25, 12, 12, 13};
    /** Below this, one order per sector every month gets too small to be practical. */
    private static final double DIRECT_ROTATE_BELOW = 6000;

    /*
     * FEES, TAX AND UNCERTAINTY per instrument, keyed by item key (or its
     * prefix). fee = typical yearly cost of a Direct plan, %; tax = how the
     * gain is taxed; spread = ± on the yearly return for the low/high range.
     * Tax rules as of FY 2025-26: equity LTCG 12.5% above ₹1.25 lakh a year
     * (STCG 20% within a year); gold ETFs 12.5% after 12 months; debt and
     * liquid funds at slab on sale; deposit and savings interest at slab every
     * year; SGBs tax-free held to maturity. 4% cess.
     */
    private record Cost(double fee, String tax, double spread) {}

    private static final Map<String, Cost> COSTS = Map.ofEntries(
            Map.entry("mf_large", new Cost(0.2, "equity", 3)), Map.entry("mf_flexi", new Cost(0.7, "equity", 3)),
            Map.entry("mf_mid", new Cost(0.7, "equity", 3.5)), Map.entry("mf_small", new Cost(0.7, "equity", 4)),
            Map.entry("mf", new Cost(0.5, "equity", 3)), Map.entry("direct", new Cost(0, "equity", 4)),
            Map.entry("debt_fd", new Cost(0, "interest", 0.5)), Map.entry("fd", new Cost(0, "interest", 0.5)),
            Map.entry("debt_short", new Cost(0.35, "slab", 1)), Map.entry("debt_corp", new Cost(0.35, "slab", 1)),
            Map.entry("gold_etf", new Cost(0.5, "gold", 3)), Map.entry("gold", new Cost(0.5, "gold", 3)),
            Map.entry("gold_sgb", new Cost(0, "sgb", 3)),
            Map.entry("cash_savings", new Cost(0, "interest", 0.5)), Map.entry("cash_liquid", new Cost(0.2, "slab", 0.5)),
            Map.entry("cash", new Cost(0.1, "interest", 0.5)));
    private static final double EQUITY_LTCG = 12.5, EQUITY_STCG = 20, EQUITY_LTCG_EXEMPT = 125000, GOLD_LTCG = 12.5, CESS = 4;
    /** The part of the SGB return that is interest, taxed every year at slab. */
    private static final double SGB_INTEREST_PART = 0.75;
    /** Slab used when income wasn't shared. */
    private static final int DEFAULT_SLAB = 20;

    // ================================================================ splits

    private record Split(List<Part> parts, List<String> reasons) {}

    private static Part part(String key, Category c, double pct, double monthly) {
        return new Part(key, c.label(), c.what(), c.rate(), c.color(), pct, monthly);
    }

    private static List<Part> nonZero(List<Part> parts) {
        return parts.stream().filter(p -> p.monthly() > 0).toList();
    }

    Split mutualFundSplit(RiskBand band, double mfMonthly, FinancialSnapshot snap, int years) {
        int[] base = MF_SPLIT.getOrDefault(band, MF_SPLIT.get(RiskBand.BALANCED));
        double[] pct = {base[0], base[1], base[2], base[3]};
        List<String> reasons = new ArrayList<>();
        if (years <= 3) {
            boolean changed = capInto(pct, 3, 0);
            changed = capInto(pct, 2, 10) || changed;
            if (changed) reasons.add("You're planning over " + years + " year" + (years > 1 ? "s" : "")
                    + ", so small caps are left out and mid caps kept to 10% — they can take years to recover from a fall.");
        } else if (snap != null && snap.profile() == ProfileType.RETIREE) {
            boolean changed = capInto(pct, 3, 0);
            changed = capInto(pct, 2, 10) || changed;
            if (changed) reasons.add("In retirement you may need to withdraw during a downturn, so small caps are left out and mid caps kept to 10%.");
        }

        double[] monthly = new double[4];
        for (int i = 0; i < 4; i++) monthly[i] = mfMonthly * pct[i] / 100;

        // A slice too small for its own SIP moves up to the next larger
        // category (small → mid → flexi → large); if even the large-cap core
        // is then under the minimum, it all goes into one index fund.
        boolean folded = false;
        for (int i = 3; i > 0; i--) {
            if (monthly[i] > 0 && monthly[i] < MIN_SIP) {
                monthly[i - 1] += monthly[i];
                pct[i - 1] += pct[i];
                monthly[i] = 0;
                pct[i] = 0;
                folded = true;
            }
        }
        boolean othersNonZero = monthly[1] > 0 || monthly[2] > 0 || monthly[3] > 0;
        if (monthly[0] < MIN_SIP && othersNonZero) {
            for (int i = 1; i < 4; i++) {
                monthly[0] += monthly[i];
                pct[0] += pct[i];
                monthly[i] = 0;
                pct[i] = 0;
            }
            folded = true;
        }
        List<Part> parts = new ArrayList<>();
        for (int i = 0; i < 4; i++) parts.add(part(MF_ORDER.get(i), MF.get(MF_ORDER.get(i)), pct[i], monthly[i]));
        if (folded) {
            long funds = parts.stream().filter(p -> p.monthly() > 0).count();
            reasons.add("At " + inr(mfMonthly) + " a month, splitting across every category would leave some funds under " + inr(MIN_SIP)
                    + " — below most funds' minimum SIP — so it's combined into " + (funds == 1 ? "one fund" : funds + " funds")
                    + ". Add categories as the amount grows.");
        }
        return new Split(nonZero(parts), reasons);
    }

    /** Caps pct[idx] at max, moving the excess into large cap. */
    private static boolean capInto(double[] pct, int idx, double max) {
        if (pct[idx] > max) {
            pct[0] += pct[idx] - max;
            pct[idx] = max;
            return true;
        }
        return false;
    }

    Split debtSplit(double debtMonthly, int years, FinancialSnapshot snap) {
        boolean retiree = snap != null && snap.profile() == ProfileType.RETIREE;
        double[] pct;
        List<String> reasons = new ArrayList<>();
        if (years <= 1) {
            pct = new double[]{100, 0, 0};
            reasons.add("Over a single year, a deposit is simplest — a debt fund's small price swings don't have time to even out.");
        } else if (years <= 3) {
            pct = new double[]{50, 50, 0};
            reasons.add("Over " + years + " years the debt money stays short-term: half in deposits, half in a short duration fund, and no corporate bond fund yet.");
        } else {
            pct = new double[]{30, 30, 40};
        }
        if (retiree && pct[2] > 0) {
            double move = Math.min(20, pct[2]);
            pct[2] -= move;
            pct[0] += move;
            reasons.add("In retirement, more stays in deposits for steady, predictable income — and senior-citizen deposits usually pay about "
                    + pct(SENIOR_FD_BONUS) + "% more.");
        }
        double[] monthly = new double[3];
        for (int i = 0; i < 3; i++) monthly[i] = debtMonthly * pct[i] / 100;
        boolean folded = false;
        for (int i = 1; i < 3; i++) {
            if (monthly[i] > 0 && monthly[i] < MIN_SIP) {
                monthly[0] += monthly[i];
                pct[0] += pct[i];
                monthly[i] = 0;
                pct[i] = 0;
                folded = true;
            }
        }
        if (folded) reasons.add("At " + inr(debtMonthly) + " a month, a debt-fund share would be under " + inr(MIN_SIP)
                + " — below most funds' minimum SIP — so it's kept in the recurring deposit instead.");
        List<Part> parts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String k = DEBT_ORDER.get(i);
            Category c = DEBT.get(k);
            double rate = c.rate() + ("fd".equals(k) && retiree ? SENIOR_FD_BONUS : 0);
            parts.add(new Part(k, c.label(), c.what(), rate, c.color(), pct[i], monthly[i]));
        }
        return new Split(nonZero(parts), reasons);
    }

    Split goldSplit(double goldMonthly, int years) {
        double etf = 100, sgb = 0;
        List<String> reasons = new ArrayList<>();
        if (years < 5) {
            reasons.add("Over " + years + " year" + (years == 1 ? "" : "s") + ", it all goes to a gold ETF: Sovereign Gold Bonds are best held to maturity for the tax-free gain, and they can be hard to sell quickly on the exchange.");
        } else if (goldMonthly * 0.5 < SGB_MIN_MONTHLY) {
            reasons.add("At " + inr(goldMonthly) + " a month, half would be under " + inr(SGB_MIN_MONTHLY)
                    + " — too little to buy a 1-gram Sovereign Gold Bond unit regularly — so it all goes to a gold ETF.");
        } else {
            etf = 50;
            sgb = 50;
            reasons.add("Set the Sovereign Gold Bond share aside each month (the liquid fund works), and buy units on the exchange whenever it covers one or more grams.");
            // 8-year tenure; the last tranche was issued in February 2024.
            reasons.add("Every Sovereign Gold Bond matures by 2032 at the latest. When yours matures, move the tax-free proceeds into the gold ETF.");
        }
        List<Part> parts = List.of(part("etf", GOLD.get("etf"), etf, goldMonthly * etf / 100),
                part("sgb", GOLD.get("sgb"), sgb, goldMonthly * sgb / 100));
        return new Split(nonZero(parts), reasons);
    }

    Split cashSplit(double cashMonthly, FinancialSnapshot snap) {
        boolean retiree = snap != null && snap.profile() == ProfileType.RETIREE;
        double savings = retiree ? 40 : 25, liquid = retiree ? 60 : 75;
        List<String> reasons = new ArrayList<>();
        if (retiree) reasons.add("In retirement, a bigger share stays in the savings account so each month's expenses are always at hand.");
        if (cashMonthly * liquid / 100 < MIN_SIP) {
            savings = 100;
            liquid = 0;
            reasons.add("At " + inr(cashMonthly) + " a month, the liquid-fund share would be under " + inr(MIN_SIP)
                    + " — below most funds' minimum SIP — so it stays in the savings account for now.");
        }
        List<Part> parts = List.of(part("savings", CASH.get("savings"), savings, cashMonthly * savings / 100),
                part("liquid", CASH.get("liquid"), liquid, cashMonthly * liquid / 100));
        return new Split(nonZero(parts), reasons);
    }

    Split sectorSplit(double directMonthly, RiskBand band, FinancialSnapshot snap) {
        boolean retiree = snap != null && snap.profile() == ProfileType.RETIREE;
        boolean defensive = band == RiskBand.CONSERVATIVE || band == RiskBand.MODERATE || retiree;
        int[] pct = defensive ? SECTOR_DEFENSIVE : SECTOR_BASE;
        List<String> reasons = new ArrayList<>();
        if (defensive) reasons.add("For a " + (retiree ? "retiree" : band.name().toLowerCase() + " investor")
                + ", the mix leans toward consumer staples and healthcare, whose earnings hold up better in a slowdown.");
        List<Part> parts = new ArrayList<>();
        for (int i = 0; i < SECTOR_ORDER.size(); i++) {
            String k = SECTOR_ORDER.get(i);
            parts.add(part(k, SECTORS.get(k), pct[i], directMonthly * pct[i] / 100));
        }
        return new Split(parts, reasons);
    }

    // ============================================================ mix views

    static double equityShare(double monthlyTotal, Map<String, Integer> al, Set<String> vehicles, String vehicle) {
        if (!vehicles.contains(vehicle)) return 0;
        double equity = monthlyTotal * al.get("equity") / 100.0;
        return vehicles.contains("mf") && vehicles.contains("direct") ? equity / 2 : equity;
    }

    public MixView fundMix(double monthlyTotal, Map<String, Integer> al, Set<String> vehicles, RiskBand band,
                           FinancialSnapshot snap, int years) {
        double mfMonthly = equityShare(monthlyTotal, al, vehicles, "mf");
        if (mfMonthly <= 0) return null;
        Split split = mutualFundSplit(band, mfMonthly, snap, years);
        String bandName = band.name().toLowerCase();
        String intro = "Of the " + inr(mfMonthly) + " a month going into equity mutual funds, here's how "
                + (bandName.matches("^[aeiou].*") ? "an " : "a ") + bandName
                + " investor could split it by company size. Smaller companies can grow faster, but fall harder.";
        List<String> notes = new ArrayList<>(split.reasons());
        notes.add("Pick the Direct plan of each fund, not the Regular one — it's the same portfolio with a lower yearly fee.");
        notes.add("One fund per category is enough. Several large-cap funds mostly own the same companies.");
        if (snap != null && snap.profile() == ProfileType.PROFESSIONAL && split.parts().stream().anyMatch(p -> p.key().equals("flexi"))) {
            notes.add("On the old tax regime, an ELSS fund can take the flexi-cap slot: it invests across company sizes too, counts toward 80C, and is locked in for 3 years.");
        }
        return new MixView(intro, split.parts(), notes, mfMonthly);
    }

    public MixView directMix(double monthlyTotal, Map<String, Integer> al, Set<String> vehicles, RiskBand band,
                             FinancialSnapshot snap, int years) {
        double directMonthly = equityShare(monthlyTotal, al, vehicles, "direct");
        if (directMonthly <= 0) return null;
        Split split = sectorSplit(directMonthly, band, snap);
        List<String> notes = new ArrayList<>();
        notes.add("Starts from the Nifty 50's own sector mix, with banks and financial services capped at 25% — they're over a third of the index.");
        notes.addAll(split.reasons());
        if (directMonthly < DIRECT_ROTATE_BELOW) {
            notes.add("At " + inr(directMonthly) + " a month, buying into all six sectors every month means very small orders. "
                    + "Instead, put each month's amount into one or two sectors in turn — over a few months you'll reach these shares.");
        }
        notes.add("Aim for about 10–20 companies in total, with no single company above 10% of your shares.");
        if (years <= 3) notes.add("You're planning over " + years + " year" + (years > 1 ? "s" : "")
                + " — individual shares can take longer than that to recover from a fall, so an index fund may suit it better.");
        return new MixView("Of the " + inr(directMonthly) + " a month for direct shares, here's how it could spread across sectors. "
                + "It's projected at the same " + pct(RATE_DIRECT) + "% whichever sectors you pick.", split.parts(), notes, directMonthly);
    }

    public MixView debtMix(double monthlyTotal, Map<String, Integer> al, int years, FinancialSnapshot snap) {
        double debtMonthly = monthlyTotal * al.get("debt") / 100.0;
        if (debtMonthly <= 0) return null;
        Split split = debtSplit(debtMonthly, years, snap);
        List<String> notes = new ArrayList<>(split.reasons());
        if (split.parts().size() > 1 || !split.parts().getFirst().key().equals("fd")) {
            notes.add("Since April 2023, debt-fund gains are taxed at your income-tax slab rate, like deposit interest — but only when you sell, while deposit interest is taxed every year.");
            notes.add("Debt funds aren't insured like bank deposits, so choose high-rated, low-cost Direct plans.");
        }
        if (snap != null && snap.profile() == ProfileType.RETIREE) {
            notes.add("At 60+, also compare the Senior Citizens' Savings Scheme: government-backed, a 5-year term, and it usually pays more than a bank deposit.");
        }
        return new MixView("Of the " + inr(debtMonthly) + " a month in the debt portion, here's how it could split between bank deposits and debt funds for a "
                + years + "-year plan.", split.parts(), notes, debtMonthly);
    }

    public MixView goldMix(double monthlyTotal, Map<String, Integer> al, int years) {
        double goldMonthly = monthlyTotal * al.get("gold") / 100.0;
        if (goldMonthly <= 0) return null;
        Split split = goldSplit(goldMonthly, years);
        List<String> notes = new ArrayList<>(split.reasons());
        notes.add("Both avoid the making charges, storage and purity worries of jewellery or coins.");
        return new MixView("Of the " + inr(goldMonthly) + " a month for gold, here's how it could split between a gold ETF and Sovereign Gold Bonds for a "
                + years + "-year plan.", split.parts(), notes, goldMonthly);
    }

    public MixView cashMix(double monthlyTotal, Map<String, Integer> al, FinancialSnapshot snap) {
        double cashMonthly = monthlyTotal * al.get("cash") / 100.0;
        if (cashMonthly <= 0) return null;
        Split split = cashSplit(cashMonthly, snap);
        List<String> notes = new ArrayList<>(split.reasons());
        // Zero spending (no regular income) gets the general note, not "keep about ₹0".
        notes.add(snap != null && snap.spend() != null && snap.spend() > 0
                ? "Your emergency fund belongs here too: keep about " + amount(snap.spend())
                + " (one month of your spending) in the savings account for instant access, and the rest of it in the liquid fund, where it earns more."
                : "Your emergency fund belongs here too: about a month of spending in the savings account, the rest in a liquid fund.");
        return new MixView("Of the " + inr(cashMonthly) + " a month kept as cash, here's how it could split between instant access and a little more interest.",
                split.parts(), notes, cashMonthly);
    }

    // ============================================================ projection

    private record Line(String key, String label, String color, double monthly, double rate) {}

    List<Line> instruments(double monthlyTotal, Map<String, Integer> al, Set<String> vehicles, RiskBand band,
                           FinancialSnapshot snap, int years) {
        List<Line> items = new ArrayList<>();
        double equityMonthly = monthlyTotal * al.get("equity") / 100.0;
        boolean useMf = vehicles.contains("mf"), useDirect = vehicles.contains("direct");
        int equitySplits = useMf && useDirect ? 2 : 1;
        if (useMf) {
            mutualFundSplit(band, equityShare(monthlyTotal, al, vehicles, "mf"), snap, years).parts()
                    .forEach(p -> items.add(new Line("mf_" + p.key(), p.label(), p.color(), p.monthly(), p.rate())));
        }
        if (useDirect) items.add(new Line("direct", "Direct equity shares", "var(--a-equity-direct)", equityMonthly / equitySplits, RATE_DIRECT));
        if (!useMf && !useDirect) items.add(new Line("mf", "Mutual funds (index / equity)", "var(--a-equity-mf)", equityMonthly, RATE_MF));
        debtSplit(monthlyTotal * al.get("debt") / 100.0, years, snap).parts()
                .forEach(p -> items.add(new Line("debt_" + p.key(), p.label(), p.color(), p.monthly(), p.rate())));
        goldSplit(monthlyTotal * al.get("gold") / 100.0, years).parts()
                .forEach(p -> items.add(new Line("gold_" + p.key(), p.label(), p.color(), p.monthly(), p.rate())));
        cashSplit(monthlyTotal * al.get("cash") / 100.0, snap).parts()
                .forEach(p -> items.add(new Line("cash_" + p.key(), p.label(), p.color(), p.monthly(), p.rate())));
        return items;
    }

    /**
     * Marginal income-tax rate (%) under the new regime's FY 2025-26 slabs,
     * from annual take-home. An estimate: take-home is a little below taxable
     * income, and the ₹12 lakh rebate is ignored, so it errs on the high side.
     */
    static int marginalSlab(FinancialSnapshot snap) {
        if (snap == null || snap.income() == null) return DEFAULT_SLAB;
        double annual = snap.income() * 12;
        double[][] bands = {{400000, 0}, {800000, 5}, {1200000, 10}, {1600000, 15}, {2000000, 20}, {2400000, 25}};
        for (double[] b : bands) if (annual <= b[0]) return (int) b[1];
        return 30;
    }

    private record Sim(double value, double invested, List<Double> values, List<Double> investedByYear) {}

    /**
     * Month-by-month SIP: each month's contribution goes in at the start of
     * the month and grows at the monthly rate; every 12 months the
     * contribution rises by stepUpPct. With no step-up it matches
     * {@code sipFutureValue} exactly.
     */
    static Sim simulate(double monthly, double annualPct, int years, double stepUpPct) {
        double r = annualPct / 100 / 12, v = 0, put = 0, amt = monthly;
        List<Double> values = new ArrayList<>(List.of(0.0)), invested = new ArrayList<>(List.of(0.0));
        for (int m = 0; m < years * 12; m++) {
            if (m > 0 && m % 12 == 0) amt *= 1 + stepUpPct / 100;
            v = (v + amt) * (1 + r);
            put += amt;
            if ((m + 1) % 12 == 0) {
                values.add(v);
                invested.add(put);
            }
        }
        return new Sim(v, put, values, invested);
    }

    public Projection project(double monthlyTotal, int years, Map<String, Integer> al, Set<String> vehicles,
                              RiskBand band, FinancialSnapshot snap, double stepUp) {
        int slab = marginalSlab(snap);
        List<Item> items = new ArrayList<>();
        for (Line l : instruments(monthlyTotal, al, vehicles, band, snap, years)) {
            Cost cost = COSTS.getOrDefault(l.key(), COSTS.getOrDefault(l.key().split("_")[0], new Cost(0, "slab", 1)));
            Sim mid = simulate(l.monthly(), l.rate(), years, stepUp);
            Sim low = simulate(l.monthly(), Math.max(0, l.rate() - cost.spread()), years, stepUp);
            Sim high = simulate(l.monthly(), l.rate() + cost.spread(), years, stepUp);
            // After fees, and after tax that's charged every year on interest.
            double netRate = l.rate() - cost.fee()
                    - ("interest".equals(cost.tax()) ? l.rate() * slab / 100.0 : 0)
                    - ("sgb".equals(cost.tax()) ? SGB_INTEREST_PART * slab / 100.0 : 0);
            Sim net = simulate(l.monthly(), netRate, years, stepUp);
            items.add(new Item(l.key(), l.label(), l.color(), l.monthly(), l.rate(), cost.fee(), cost.tax(), cost.spread(),
                    mid.value(), mid.invested(), mid.value() - mid.invested(), net.value(),
                    mid.values(), low.values(), high.values(), mid.investedByYear()));
        }

        // Tax on the gains when everything is withdrawn at the end.
        double equityGain = 0, tax = 0;
        for (Item it : items) {
            double netGain = Math.max(0, it.netValue() - it.invested());
            switch (it.tax()) {
                case "equity" -> equityGain += netGain;
                case "slab" -> tax += netGain * slab / 100.0;
                case "gold" -> tax += netGain * (years > 1 ? GOLD_LTCG : slab) / 100.0;
                default -> { }
            }
        }
        tax += years > 1 ? Math.max(0, equityGain - EQUITY_LTCG_EXEMPT) * EQUITY_LTCG / 100 : equityGain * EQUITY_STCG / 100;
        tax *= 1 + CESS / 100;

        double totalInvested = items.stream().mapToDouble(Item::invested).sum();
        double totalProjected = items.stream().mapToDouble(Item::projected).sum();
        double afterFeesAndTax = items.stream().mapToDouble(Item::netValue).sum() - tax;
        double blended = monthlyTotal > 0 ? items.stream().mapToDouble(it -> it.rate() * it.monthly()).sum() / monthlyTotal : 0;

        List<YearPoint> series = new ArrayList<>();
        for (int y = 0; y <= years; y++) {
            final int yy = y;
            series.add(new YearPoint(y,
                    items.stream().mapToDouble(it -> it.investedByYear().get(yy)).sum(),
                    items.stream().mapToDouble(it -> it.values().get(yy)).sum(),
                    items.stream().mapToDouble(it -> it.low().get(yy)).sum(),
                    items.stream().mapToDouble(it -> it.high().get(yy)).sum()));
        }
        YearPoint last = series.getLast();
        return new Projection(items, totalInvested, totalProjected, totalProjected - totalInvested, blended, series,
                last.low(), last.high(), afterFeesAndTax, tax, slab, snap == null || snap.income() == null, stepUp,
                totalProjected / Math.pow(1 + SnapshotCalculator.INFLATION_PCT / 100, years));
    }

    // ============================================================ priorities

    /** The ordered monthly steps: debt, then the emergency fund, then investing. */
    public Priorities priorities(FinancialSnapshot s, Waterfall flow) {
        List<Priority> items = new ArrayList<>();
        String intro = flow == null
                ? (s.income() != null && s.income() == 0
                ? "You don't have a regular income yet, so there are no monthly amounts — but the same order applies to any money that comes your way, like a gift or your first stipend."
                : "You didn't share your income, so these are in order of priority without rupee amounts.")
                : "Of the " + inr(roundTo(flow.budget(), 100)) + " a month you can set aside (" + Math.round(Math.max(s.savingsRate(), 10))
                + "% of income), here's where each rupee should go first.";
        if (s.highInterestDebt() == null || s.highInterestDebt()) {
            boolean has = Boolean.TRUE.equals(s.highInterestDebt());
            items.add(new Priority(has ? "Pay down high-interest debt" : "Check for high-interest debt",
                    has ? "At ~" + pct(HIGH_INTEREST_PCT) + "% a year, every rupee repaid beats any return below."
                            : "You weren't sure — if any card balance or app loan is running, clear it before investing.",
                    flow != null && flow.toDebt() > 0 ? inr(flow.toDebt()) + "/mo" : ""));
        }
        if (s.efMonths() == null || s.efMonths() < s.efTargetMonths()) {
            items.add(new Priority("Build your emergency fund",
                    flow != null
                            ? "Liquid fund or sweep-in FD, up to " + amount(s.efTarget()) + " (" + s.efTargetMonths() + " months of spending) — about "
                            + flow.efMonthsToFill() + " month" + (flow.efMonthsToFill() == 1 ? "" : "s") + " at this pace"
                            + (flow.toDebt() > 0 ? ", sooner once the debt is gone." : ".")
                            : "Aim for " + s.efTargetMonths() + " months of essential spending somewhere you can withdraw any day.",
                    flow != null && flow.toEf() > 0 ? inr(flow.toEf()) + "/mo" : ""));
        }
        items.add(new Priority("Invest the rest",
                items.isEmpty() ? "Your cushion and debt are in order, so all of it can be invested." : "Once the steps above are done, move their share here too.",
                flow != null ? inr(flow.toInvest()) + "/mo" : ""));
        return new Priorities(intro, items);
    }
}
