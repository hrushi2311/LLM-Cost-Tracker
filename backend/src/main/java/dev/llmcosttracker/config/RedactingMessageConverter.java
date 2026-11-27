package dev.llmcosttracker.config;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logback converter for {@code %m}/{@code %msg}/{@code %message} that masks credentials before anything is written:
 * values of Authorization, x-api-key and X-Tracker-* headers, password fields, bearer tokens, OpenAI-style ({@code sk-…}) or
 * tracker ({@code trk_…}) keys, and the key segment of {@code /proxy/k/{key}/…} paths (whatever its format). The
 * tracker never logs these on purpose; this is the safety net for library, error and access logs.
 */
public class RedactingMessageConverter extends MessageConverter {

    static final String MASK = "[REDACTED]";

    private static final Pattern HEADER_VALUE = Pattern.compile(
            "(?i)(\\b(?:authorization|proxy-authorization|x-api-key|api-key|x-tracker-[a-z0-9-]+)\\b[\"']?\\s*[:=]\\s*[\"']?)"
                    + "((?:bearer|basic)\\s+)?[^\\s,;\"'\\]}]+");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer\\s+)[A-Za-z0-9._~+/=-]+");
    private static final Pattern PROVIDER_KEY = Pattern.compile("\\b(sk-(?:ant-)?)[A-Za-z0-9_-]{8,}");
    private static final Pattern TRACKER_KEY = Pattern.compile("\\b(trk_)[A-Za-z0-9]{8,}");
    /** {@code password=…} / {@code "password":"…"}: up to the closing quote, separator or bracket (spaces too). */
    private static final Pattern PASSWORD = Pattern.compile(
            "(?i)(\\b(?:password|passwd|secret|x-csrf-token|csrf_?token)\\b[\"']?\\s*[:=]\\s*[\"']?)[^\"'&,\\]}\\r\\n]*");
    private static final Pattern PATH_KEY = Pattern.compile("(/proxy/k/)[^/?#\\s\"']+");

    @Override
    public String convert(ILoggingEvent event) {
        return redact(super.convert(event));
    }

    public static String redact(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String out = redactPathKey(message);
        out = replace(PASSWORD, out, m -> m.group(1) + MASK);
        out = replace(HEADER_VALUE, out, m -> m.group(1) + (m.group(2) != null ? m.group(2) : "") + MASK);
        out = replace(BEARER, out, m -> m.group(1) + MASK);
        out = replace(PROVIDER_KEY, out, m -> m.group(1) + MASK);
        out = replace(TRACKER_KEY, out, m -> m.group(1) + MASK);
        return out;
    }

    /** Masks the key in {@code /proxy/k/{key}/…}; also used directly by the access log. */
    public static String redactPathKey(String text) {
        return text == null ? null : replace(PATH_KEY, text, m -> m.group(1) + MASK);
    }

    private static String replace(Pattern p, String input, java.util.function.Function<Matcher, String> f) {
        Matcher m = p.matcher(input);
        if (!m.find()) {
            return input;
        }
        StringBuilder sb = new StringBuilder();
        do {
            m.appendReplacement(sb, Matcher.quoteReplacement(f.apply(m)));
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }
}
