package dev.llmcosttracker.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Where a budget stands in its current period.
 *
 * <p>Projection (SPEC.md): spend so far ÷ time elapsed × period length. "Days elapsed" is taken as exact elapsed
 * time, so a daily budget projects too; elapsed time is floored at one hour so a single early call does not
 * project to a huge figure. The alert date is when the same rate would reach {@code alert_pct}, if that falls
 * inside the period.
 *
 * @param spentUsd           spend in the current period
 * @param usedFraction       spent ÷ limit (may exceed 1)
 * @param projectedUsd       spend at the end of the period at the current rate
 * @param thresholdReached   spend has reached alert_pct of the limit
 * @param exceeded           spend has reached the limit
 * @param projectedAlertDate date (in TRACKER_TIMEZONE) the threshold is likely crossed; null if reached or not in
 *                           this period
 */
public record BudgetStatus(
        BudgetPeriod period,
        BigDecimal spentUsd,
        BigDecimal usedFraction,
        BigDecimal projectedUsd,
        boolean thresholdReached,
        boolean exceeded,
        LocalDate projectedAlertDate) {

    static final Duration MIN_ELAPSED = Duration.ofHours(1);

    public static BudgetStatus of(Budget budget, BigDecimal spent, Instant now, ZoneId zone) {
        BudgetPeriod p = BudgetPeriod.containing(budget.period(), now, zone);
        BigDecimal s = spent == null ? BigDecimal.ZERO : spent;
        long elapsedMs = Math.max(MIN_ELAPSED.toMillis(), Duration.between(p.start(), now).toMillis());
        elapsedMs = Math.min(elapsedMs, p.length().toMillis());
        BigDecimal projected = s.multiply(BigDecimal.valueOf(p.length().toMillis()))
                .divide(BigDecimal.valueOf(elapsedMs), 8, RoundingMode.HALF_UP);
        BigDecimal threshold = budget.limitUsd().multiply(BigDecimal.valueOf(budget.alertPct()))
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        boolean reached = s.compareTo(threshold) >= 0;
        LocalDate alertDate = null;
        if (!reached && s.signum() > 0) {
            // time to threshold at the current rate = elapsed × threshold ÷ spent
            long msToThreshold = threshold.multiply(BigDecimal.valueOf(elapsedMs))
                    .divide(s, 0, RoundingMode.HALF_UP).longValueExact();
            Instant at = p.start().plusMillis(msToThreshold);
            if (at.isBefore(p.end())) {
                alertDate = at.atZone(zone).toLocalDate();
            }
        }
        return new BudgetStatus(p, s, s.divide(budget.limitUsd(), 4, RoundingMode.HALF_UP), projected, reached,
                s.compareTo(budget.limitUsd()) >= 0, alertDate);
    }
}
