package com.moneycompass.engine;

import java.util.Map;

/**
 * Compounding maths and the illustrative long-term return assumptions shared
 * by the investment planner, the narratives and the assistant. The rates are
 * not guarantees — real returns vary and can be negative in any given year.
 */
public final class PlanMath {

    private PlanMath() {
    }

    /** Illustrative long-term annual returns (%). */
    public static final double RATE_FD = 6.5;
    public static final double RATE_MF = 11;
    public static final double RATE_DIRECT = 13;
    public static final double RATE_GOLD = 8;
    public static final double RATE_CASH = 4;

    /** Future value of a monthly SIP, compounding monthly, contributions at the start of each month. */
    public static double sipFutureValue(double monthly, double annualPct, double years) {
        if (monthly <= 0 || years <= 0) return 0;
        double r = annualPct / 100 / 12;
        long n = Math.round(years * 12);
        if (r == 0) return monthly * n;
        return monthly * ((Math.pow(1 + r, n) - 1) / r) * (1 + r);
    }

    /** Future value of a SIP raised by {@code stepUpPct} once a year. */
    public static double stepUpFutureValue(double monthly, double annualPct, int years, double stepUpPct) {
        double r = annualPct / 100 / 12, fv = 0, m = monthly;
        for (int y = 0; y < years; y++) {
            for (int k = 0; k < 12; k++) fv = (fv + m) * (1 + r);
            m *= 1 + stepUpPct / 100;
        }
        return fv;
    }

    /** The long-run return a four-bucket allocation assumes, weighted by its percentages. */
    public static double blendedReturn(Map<String, Integer> allocation) {
        return (allocation.get("equity") * RATE_MF + allocation.get("debt") * RATE_FD
                + allocation.get("gold") * RATE_GOLD + allocation.get("cash") * RATE_CASH) / 100.0;
    }

    public static double round1(double d) {
        return Math.round(d * 10) / 10.0;
    }

    /** 7.8 → "7.8", 11.0 → "11". */
    public static String pct(double d) {
        double r = round1(d);
        return r == Math.rint(r) ? Long.toString((long) r) : Double.toString(r);
    }
}
