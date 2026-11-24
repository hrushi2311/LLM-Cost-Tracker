package dev.llmcosttracker.pricing;

import dev.llmcosttracker.provider.Usage;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * cost = (T_in·P_in + T_out·P_out + T_cr·P_cr + T_cw·P_cw) / 10^6, with a missing cache price falling back to the
 * input price. Reasoning tokens are never added (they are already inside output tokens). Result scale matches
 * {@code usage_event.cost_usd NUMERIC(14,8)}.
 */
public final class CostCalculator {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private CostCalculator() {}

    public static BigDecimal cost(Usage usage, ModelPrice price) {
        BigDecimal cacheRead = price.cacheReadPerMtok() != null ? price.cacheReadPerMtok() : price.inputPerMtok();
        BigDecimal cacheWrite = price.cacheWritePerMtok() != null ? price.cacheWritePerMtok() : price.inputPerMtok();
        BigDecimal total = price.inputPerMtok().multiply(BigDecimal.valueOf(usage.inputTokens()))
                .add(price.outputPerMtok().multiply(BigDecimal.valueOf(usage.outputTokens())))
                .add(cacheRead.multiply(BigDecimal.valueOf(usage.cacheReadTokens())))
                .add(cacheWrite.multiply(BigDecimal.valueOf(usage.cacheWriteTokens())));
        return total.divide(MILLION, 8, RoundingMode.HALF_UP);
    }
}
