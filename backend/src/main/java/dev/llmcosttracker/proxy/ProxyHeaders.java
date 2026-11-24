package dev.llmcosttracker.proxy;

import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpHeaders;

/** Header copying rules for both directions. Authorization and x-api-key pass through and are never logged. */
public final class ProxyHeaders {

    public static final String TRACKER_PREFIX = "x-tracker-";
    public static final String PROJECT_KEY = "X-Tracker-Key";

    /** RFC 9110 §7.6.1 hop-by-hop headers, plus the legacy ones proxies also drop. */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "proxy-connection",
            "te", "trailer", "trailers", "transfer-encoding", "upgrade");

    /** Set by the HTTP client from the forwarded body and target URI. */
    private static final Set<String> REQUEST_ONLY = Set.of("host", "content-length", "expect");

    private ProxyHeaders() {}

    /**
     * @param dropAcceptEncoding true when the proxy needs to parse the response, so upstream sends it uncompressed
     */
    public static void copyRequestHeaders(HttpHeaders from, HttpHeaders to, boolean dropAcceptEncoding) {
        Set<String> listedInConnection = connectionTokens(from);
        from.forEach((name, values) -> {
            String n = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(n) || REQUEST_ONLY.contains(n) || n.startsWith(TRACKER_PREFIX)
                    || listedInConnection.contains(n)
                    || (dropAcceptEncoding && n.equals("accept-encoding"))) {
                return;
            }
            to.addAll(name, values);
        });
    }

    public static void copyResponseHeaders(HttpHeaders from, HttpHeaders to) {
        Set<String> listedInConnection = connectionTokens(from);
        from.forEach((name, values) -> {
            String n = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(n) || listedInConnection.contains(n)) {
                return;
            }
            to.addAll(name, values);
        });
    }

    private static Set<String> connectionTokens(HttpHeaders headers) {
        return headers.getConnection().stream()
                .map(t -> t.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
    }
}
