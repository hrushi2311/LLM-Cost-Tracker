package dev.llmcosttracker.api;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * A half-open range {@code [from, to)} from the {@code from}/{@code to} query parameters. Each accepts an ISO-8601
 * instant ({@code 2026-11-01T00:00:00Z}), an offset date-time, or a date (midnight in {@code TRACKER_TIMEZONE}). Default:
 * the last 14 days up to now.
 */
public record TimeRange(Instant from, Instant to) {

    public static final Duration DEFAULT_SPAN = Duration.ofDays(14);
    public static final Duration MAX_SPAN = Duration.ofDays(400);

    public static TimeRange parse(String from, String to, Instant now, ZoneId zone) {
        Instant end = to == null || to.isBlank() ? now : instant("to", to, zone);
        Instant start = from == null || from.isBlank() ? end.minus(DEFAULT_SPAN) : instant("from", from, zone);
        if (!start.isBefore(end)) {
            throw ApiException.validation("'from' must be before 'to'");
        }
        if (Duration.between(start, end).compareTo(MAX_SPAN) > 0) {
            throw ApiException.validation("The range may span at most " + MAX_SPAN.toDays() + " days");
        }
        return new TimeRange(start, end);
    }

    private static Instant instant(String name, String value, ZoneId zone) {
        String v = value.trim();
        try {
            if (v.length() == 10) {
                return LocalDate.parse(v).atStartOfDay(zone).toInstant();
            }
            return OffsetDateTime.parse(v).toInstant(); // also accepts the 'Z' form
        } catch (DateTimeParseException e) {
            throw ApiException.validation("'" + name + "' must be an ISO-8601 date or date-time");
        }
    }

    public Duration length() {
        return Duration.between(from, to);
    }

    /** The range of the same length that ends where this one starts. */
    public TimeRange previous() {
        return new TimeRange(from.minus(length()), from);
    }
}
