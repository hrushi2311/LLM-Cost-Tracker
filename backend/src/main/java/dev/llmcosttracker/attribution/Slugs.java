package dev.llmcosttracker.attribution;

import java.util.Locale;

/** Slug rules from SPEC.md: lowercase, [a-z0-9-], other characters become '-', collapse repeats, trim dashes. */
public final class Slugs {

    public static final int AGENT_MAX = 64;
    public static final int TAG_KEY_MAX = 32;

    private Slugs() {}

    /** The slug, or null when nothing is left after normalising (treat as absent). */
    public static String slug(String raw, int maxLength) {
        if (raw == null) {
            return null;
        }
        String s = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]", "-")
                .replaceAll("-{2,}", "-");
        s = trimDashes(s);
        if (s.length() > maxLength) {
            s = trimDashes(s.substring(0, maxLength));
        }
        return s.isEmpty() ? null : s;
    }

    private static String trimDashes(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '-') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == '-') {
            end--;
        }
        return s.substring(start, end);
    }
}
