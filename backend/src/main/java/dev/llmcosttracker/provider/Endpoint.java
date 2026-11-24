package dev.llmcosttracker.provider;

import java.util.Optional;
import org.springframework.http.HttpMethod;

/** The endpoints whose usage is recorded. Every other path is forwarded but not recorded. */
public enum Endpoint {
    CHAT_COMPLETIONS("chat.completions");

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
        if (dialect == Dialect.OPENAI && p.equals("/chat/completions")) {
            return Optional.of(CHAT_COMPLETIONS);
        }
        return Optional.empty();
    }
}
