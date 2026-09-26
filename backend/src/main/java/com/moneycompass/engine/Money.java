package com.moneycompass.engine;

/**
 * Rupee formatting shared by the template narratives, the assistant and the
 * plan text, so every screen writes amounts the same way as the frontend.
 */
public final class Money {

    private Money() {
    }

    public static double roundTo(double n, double step) {
        return Math.round(n / step) * step;
    }

    /** Indian digit grouping: 1234567 → ₹12,34,567. */
    public static String inr(double amount) {
        long n = Math.round(amount);
        boolean negative = n < 0;
        String s = Long.toString(Math.abs(n));
        String out;
        if (s.length() <= 3) {
            out = s;
        } else {
            String last3 = s.substring(s.length() - 3);
            String rest = s.substring(0, s.length() - 3);
            StringBuilder grouped = new StringBuilder();
            int lead = rest.length() % 2;
            if (lead == 1) grouped.append(rest.charAt(0));
            for (int i = lead; i < rest.length(); i += 2) {
                if (!grouped.isEmpty()) grouped.append(',');
                grouped.append(rest, i, i + 2);
            }
            out = grouped + "," + last3;
        }
        return (negative ? "-" : "") + "₹" + out;
    }

    /** Readable large amounts for prose: ₹4.2 lakh, ₹1.35 crore; rounded exact rupees below a lakh. */
    public static String amount(double amount) {
        long n = Math.round(amount);
        if (n >= 10_000_000) return "₹" + trim(Math.round(n / 100_000.0) / 100.0) + " crore";
        if (n >= 100_000) return "₹" + trim(Math.round(n / 10_000.0) / 10.0) + " lakh";
        return inr(roundTo(n, n >= 10_000 ? 500 : 100));
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }
}
