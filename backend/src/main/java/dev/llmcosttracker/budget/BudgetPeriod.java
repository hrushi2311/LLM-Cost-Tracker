package dev.llmcosttracker.budget;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * The budget period containing an instant: a day starts at midnight and a month on the 1st at midnight, both in
 * {@code TRACKER_TIMEZONE}. Uses {@code atStartOfDay(zone)}, so days that start at 01:00 because of a DST change
 * are still handled.
 */
public record BudgetPeriod(String period, LocalDate startDate, Instant start, Instant end) {

    public static BudgetPeriod containing(String period, Instant at, ZoneId zone) {
        LocalDate today = at.atZone(zone).toLocalDate();
        LocalDate first = switch (period) {
            case "day" -> today;
            case "month" -> today.withDayOfMonth(1);
            default -> throw new IllegalArgumentException("Unknown period");
        };
        LocalDate next = period.equals("day") ? first.plusDays(1) : first.plusMonths(1);
        return new BudgetPeriod(period, first, first.atStartOfDay(zone).toInstant(), next.atStartOfDay(zone).toInstant());
    }

    public Duration length() {
        return Duration.between(start, end);
    }

    /** The period's last calendar date (e.g. Nov 30 for November). */
    public LocalDate lastDate(ZoneId zone) {
        return ZonedDateTime.ofInstant(end.minusNanos(1), zone).toLocalDate();
    }
}
