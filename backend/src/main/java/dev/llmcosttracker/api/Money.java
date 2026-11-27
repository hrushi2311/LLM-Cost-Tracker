package dev.llmcosttracker.api;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money and ratios leave the API as decimal strings, never JSON numbers (no binary floating point anywhere). */
public final class Money {

    private Money() {}

    /** USD with the database's 8 decimal places, e.g. {@code "412.86000000"}; zero when null. */
    public static String usd(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(8, RoundingMode.HALF_UP).toPlainString();
    }

    /** A limit as stored ({@code NUMERIC(12,2)}), e.g. {@code "250.00"}. */
    public static String limit(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** A per-million-token price as stored ({@code NUMERIC(12,6)}); null stays null. */
    public static String price(BigDecimal value) {
        return value == null ? null : value.setScale(6, RoundingMode.HALF_UP).toPlainString();
    }

    /** {@code part / whole} as a fraction with 4 decimal places ("0.4170"); null when the whole is zero. */
    public static String ratio(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) {
            return null;
        }
        return (part == null ? BigDecimal.ZERO : part).divide(whole, 4, RoundingMode.HALF_UP).toPlainString();
    }

    public static String ratio(long part, long whole) {
        return ratio(BigDecimal.valueOf(part), BigDecimal.valueOf(whole));
    }
}
