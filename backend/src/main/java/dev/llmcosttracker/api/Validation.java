package dev.llmcosttracker.api;

import java.math.BigDecimal;
import java.util.UUID;

/** Input checks shared by the API controllers. Messages name the field, never the value sent. */
public final class Validation {

    private Validation() {}

    /** A required, trimmed, single-line name of 1..max characters. */
    public static String name(String field, String value, int max) {
        if (value == null || value.isBlank()) {
            throw ApiException.validation(field + " is required");
        }
        String v = value.trim();
        if (v.length() > max) {
            throw ApiException.validation(field + " must be at most " + max + " characters");
        }
        if (v.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.validation(field + " must not contain control characters");
        }
        return v;
    }

    /** A required decimal with at most {@code scale} decimal places, in [min, max] (min exclusive if asked). */
    public static BigDecimal decimal(String field, BigDecimal value, int scale, BigDecimal min, boolean minExclusive,
            BigDecimal max) {
        if (value == null) {
            throw ApiException.validation(field + " is required");
        }
        if (value.stripTrailingZeros().scale() > scale) {
            throw ApiException.validation(field + " has more than " + scale + " decimal places");
        }
        int c = value.compareTo(min);
        if (c < 0 || (minExclusive && c == 0)) {
            throw ApiException.validation(field + " must be " + (minExclusive ? "greater than " : "at least ")
                    + min.toPlainString());
        }
        if (value.compareTo(max) > 0) {
            throw ApiException.validation(field + " must be at most " + max.toPlainString());
        }
        return value;
    }

    public static UUID uuid(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.validation(field + " is required");
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.validation(field + " must be a UUID");
        }
    }
}
