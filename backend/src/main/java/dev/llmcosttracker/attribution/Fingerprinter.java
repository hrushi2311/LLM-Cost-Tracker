package dev.llmcosttracker.attribution;

import com.fasterxml.jackson.databind.JsonNode;
import dev.llmcosttracker.provider.Endpoint;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * System-prompt fingerprint: extract → normalise (trim, collapse whitespace) → optionally mask → SHA-256 hex.
 *
 * <p>The extracted prompt only ever lives in local variables of {@link #fingerprint}; it is never stored, logged
 * or returned. Only the hash leaves this class.
 */
public final class Fingerprinter {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern UUID = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern ISO_DATE = Pattern.compile(
            "\\b\\d{4}-\\d{2}-\\d{2}(?:[T ]\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:?\\d{2})?)?\\b");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");

    private final boolean mask;

    public Fingerprinter(boolean mask) {
        this.mask = mask;
    }

    /** The fingerprint of the request's system prompt, or empty when it has none. */
    public Optional<String> fingerprint(Endpoint endpoint, JsonNode request) {
        String prompt = normalise(systemPrompt(endpoint, request));
        if (prompt.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(sha256Hex(mask ? mask(prompt) : prompt));
    }

    /**
     * Chat Completions: all {@code system} and {@code developer} messages joined with newlines. Responses:
     * {@code instructions}. Anthropic Messages: top-level {@code system}, a string or the text of its blocks.
     */
    static String systemPrompt(Endpoint endpoint, JsonNode request) {
        if (request == null || !request.isObject()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        switch (endpoint) {
            case CHAT_COMPLETIONS -> {
                for (JsonNode message : request.path("messages")) {
                    String role = message.path("role").asText("");
                    if (role.equals("system") || role.equals("developer")) {
                        addText(message.path("content"), parts);
                    }
                }
            }
            case RESPONSES -> addText(request.path("instructions"), parts);
            case MESSAGES -> addText(request.path("system"), parts);
        }
        return String.join("\n", parts);
    }

    /** A string, or an array of blocks/parts whose {@code text} fields are taken (e.g. {"type":"text","text":…}). */
    private static void addText(JsonNode content, List<String> parts) {
        if (content.isTextual()) {
            parts.add(content.asText());
        } else if (content.isArray()) {
            for (JsonNode block : content) {
                if (block.isTextual()) {
                    parts.add(block.asText());
                } else if (block.path("text").isTextual()) {
                    parts.add(block.path("text").asText());
                }
            }
        }
    }

    static String normalise(String prompt) {
        return WHITESPACE.matcher(prompt.trim()).replaceAll(" ");
    }

    /** Replaces UUIDs, emails, ISO dates and numbers with placeholders (in that order, so parts are not split). */
    static String mask(String prompt) {
        String s = UUID.matcher(prompt).replaceAll("<uuid>");
        s = EMAIL.matcher(s).replaceAll("<email>");
        s = ISO_DATE.matcher(s).replaceAll("<date>");
        return NUMBER.matcher(s).replaceAll("<num>");
    }

    static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
