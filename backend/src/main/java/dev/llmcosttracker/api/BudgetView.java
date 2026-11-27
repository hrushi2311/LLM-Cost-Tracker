package dev.llmcosttracker.api;

import dev.llmcosttracker.budget.Budget;
import dev.llmcosttracker.budget.BudgetStatus;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/** A budget with its current-period standing. Money and fractions are decimal strings. */
public record BudgetView(
        UUID id,
        UUID agentId,
        String period,
        String limitUsd,
        int alertPct,
        boolean enforce,
        LocalDate periodStart,
        LocalDate periodEnd,
        String spentUsd,
        String usedFraction,
        String projectedUsd,
        boolean thresholdReached,
        boolean exceeded,
        LocalDate projectedAlertDate) {

    public static BudgetView of(Budget b, BudgetStatus s, ZoneId zone) {
        return new BudgetView(b.id(), b.agentId(), b.period(), Money.limit(b.limitUsd()), b.alertPct(), b.enforce(),
                s.period().startDate(), s.period().lastDate(zone), Money.usd(s.spentUsd()),
                s.usedFraction().toPlainString(), Money.usd(s.projectedUsd()), s.thresholdReached(), s.exceeded(),
                s.projectedAlertDate());
    }
}
