package dev.llmcosttracker.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One row of {@code model_price}; prices are USD per million tokens. Cache prices may be null. */
public record ModelPrice(
        String provider,
        String model,
        LocalDate effectiveFrom,
        BigDecimal inputPerMtok,
        BigDecimal outputPerMtok,
        BigDecimal cacheReadPerMtok,
        BigDecimal cacheWritePerMtok) {}
