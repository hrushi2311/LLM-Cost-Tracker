package dev.llmcosttracker.provider;

import java.util.Optional;
import org.springframework.http.HttpMethod;

/** The endpoints whose usage is recorded. Every other path is forwarded but not recorded. */
public enum Endpoint {
    /** OpenAI (and OpenAI-compatible) Chat Completions: {@code POST {base}/chat/completions}. */
    CHAT_COMPLETIONS("chat.completions"),
    /** OpenAI (and OpenAI-compatible) Responses: {@code POST {base}/responses}. */
    RESPONSES("responses"),
    /** Anthropic Messages: {@code POST {base}/v1/messages}. */
    MESSAGES("messages");

    private final String wireName;

    Endpoint(String wireName) {
        this.wireName = wireName;
    }

    /** Value stored in {@code usage_event.endpoint}. */
    public String wireName() {
        return wireName;
    }

    /**
     * @param path the upstream path relative to the provider base URL, e.g. {@code /chat/completions}
     */
    public static Optional<Endpoint> classify(Dialect dialect, HttpMethod method, String path) {
        if (!HttpMethod.POST.equals(method)) {
            return Optional.empty();
        }
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return switch (dialect) {
            case OPENAI -> switch (p) {
                case "/chat/completions" -> Optional.of(CHAT_COMPLETIONS);
                case "/responses" -> Optional.of(RESPONSES);
                default -> Optional.empty();
            };
            case ANTHROPIC -> p.equals("/v1/messages") ? Optional.of(MESSAGES) : Optional.empty();
        };
    }
}
