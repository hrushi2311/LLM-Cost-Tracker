package dev.llmcosttracker.attribution;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.util.UriUtils;

/**
 * Agent, run and tags for one request, worked out from headers, the path and (lazily) the fingerprint.
 *
 * @param agent the resolved agent reference (before alias lookup, which the batch writer does)
 * @param runId validated {@code X-Tracker-Run-Id}, or null
 * @param tags  validated {@code X-Tracker-Tag-*} headers, possibly empty
 */
public record RequestAttribution(AgentRef agent, String runId, Map<String, String> tags) {

    public static final String AGENT_HEADER = "X-Tracker-Agent";
    public static final String RUN_HEADER = "X-Tracker-Run-Id";
    public static final String TAG_PREFIX = "x-tracker-tag-";
    static final int RUN_ID_MAX = 128;
    static final int MAX_TAGS = 10;
    static final int TAG_VALUE_MAX = 128;

    private static final Logger log = LoggerFactory.getLogger(RequestAttribution.class);

    /**
     * Resolution order (first match wins): X-Tracker-Agent header → agent/{name} path segment → system-prompt
     * fingerprint (only computed when nothing earlier matched) → unattributed.
     *
     * @param rawPathAgent the raw, still percent-encoded agent path segment, or null
     * @param fingerprint  supplies the fingerprint hash, or null when disabled/absent
     */
    public static RequestAttribution resolve(HttpHeaders headers, String rawPathAgent, Supplier<String> fingerprint) {
        return new RequestAttribution(agent(headers, rawPathAgent, fingerprint), runId(headers), tags(headers));
    }

    static AgentRef agent(HttpHeaders headers, String rawPathAgent, Supplier<String> fingerprint) {
        String header = Slugs.slug(headers.getFirst(AGENT_HEADER), Slugs.AGENT_MAX);
        if (header != null) {
            return AgentRef.header(header);
        }
        if (rawPathAgent != null) {
            String path = Slugs.slug(UriUtils.decode(rawPathAgent, StandardCharsets.UTF_8), Slugs.AGENT_MAX);
            if (path != null) {
                return AgentRef.path(path);
            }
        }
        String fp = fingerprint.get();
        if (fp != null) {
            return AgentRef.fingerprint(fp);
        }
        return AgentRef.unattributed();
    }

    /** Optional, 1–128 printable ASCII characters; anything else is ignored. */
    static String runId(HttpHeaders headers) {
        String raw = headers.getFirst(RUN_HEADER);
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        if (v.isEmpty() || v.length() > RUN_ID_MAX || !v.chars().allMatch(c -> c >= 0x20 && c <= 0x7E)) {
            log.debug("Ignoring invalid {} header (length {})", RUN_HEADER, raw.length());
            return null;
        }
        return v;
    }

    /** Up to 10 tags; key slugged (max 32), value max 128 printable characters. Extra or invalid tags ignored. */
    static Map<String, String> tags(HttpHeaders headers) {
        Map<String, String> tags = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            if (!name.toLowerCase(Locale.ROOT).startsWith(TAG_PREFIX) || values.isEmpty()) {
                return;
            }
            String key = Slugs.slug(name.substring(TAG_PREFIX.length()), Slugs.TAG_KEY_MAX);
            String value = values.get(0) == null ? "" : values.get(0).trim();
            boolean validValue = !value.isEmpty() && value.length() <= TAG_VALUE_MAX
                    && value.chars().allMatch(c -> c >= 0x20 && c != 0x7F);
            if (key == null || !validValue) {
                log.debug("Ignoring invalid tag header");
                return;
            }
            if (tags.size() >= MAX_TAGS) {
                log.debug("Ignoring tag beyond the limit of {}", MAX_TAGS);
                return;
            }
            tags.putIfAbsent(key, value);
        });
        return Map.copyOf(tags);
    }
}
