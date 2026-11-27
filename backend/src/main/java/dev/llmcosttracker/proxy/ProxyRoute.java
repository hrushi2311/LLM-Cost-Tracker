package dev.llmcosttracker.proxy;

import java.net.URI;
import java.util.Optional;

/**
 * Parsed {@code /proxy[/k/{projectKey}]/{provider}[/agent/{agentName}]/{path...}}. Works on the raw (still
 * percent-encoded) path so the forwarded path is byte-for-byte what the client sent, minus the proxy prefix, the
 * key segment and the agent segment.
 *
 * @param pathKey      project key given in the path ({@code /proxy/k/{key}/…}), or null; never forwarded or logged
 * @param provider     provider key, e.g. {@code openai}
 * @param agentName    raw agent segment, or null
 * @param upstreamPath path to append to the provider base URL; empty or starting with {@code /}
 */
public record ProxyRoute(String pathKey, String provider, String agentName, String upstreamPath) {

    static final String PREFIX = "/proxy/";
    /** Reserved first segment for a path-based project key; no provider may be named this. */
    public static final String KEY_SEGMENT = "k";

    public static Optional<ProxyRoute> parse(String rawPath) {
        if (rawPath == null || !rawPath.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String rest = rawPath.substring(PREFIX.length());
        String key = null;
        if (rest.startsWith(KEY_SEGMENT + "/")) {
            String afterK = rest.substring(KEY_SEGMENT.length() + 1);
            int end = afterK.indexOf('/');
            if (end <= 0) {
                return Optional.empty(); // /proxy/k/{key} with no provider, or an empty key
            }
            key = afterK.substring(0, end);
            rest = afterK.substring(end + 1);
        }
        int slash = rest.indexOf('/');
        String provider = slash < 0 ? rest : rest.substring(0, slash);
        String path = slash < 0 ? "" : rest.substring(slash);
        if (provider.isEmpty()) {
            return Optional.empty();
        }
        String agent = null;
        if (path.startsWith("/agent/")) {
            String afterAgent = path.substring("/agent/".length());
            int end = afterAgent.indexOf('/');
            agent = end < 0 ? afterAgent : afterAgent.substring(0, end);
            path = end < 0 ? "" : afterAgent.substring(end);
            if (agent.isEmpty()) {
                agent = null;
            }
        }
        return Optional.of(new ProxyRoute(key, provider, agent, path));
    }

    /**
     * Joins the configured base URL with the request path and query. Refuses any result whose scheme, host or port
     * differs from the base, so request data can never redirect the proxy to another host (SSRF).
     */
    public Optional<URI> upstreamUri(URI base, String rawQuery) {
        String baseStr = base.toString().replaceAll("/+$", "");
        String full = baseStr + upstreamPath + (rawQuery == null || rawQuery.isEmpty() ? "" : "?" + rawQuery);
        try {
            URI uri = new URI(full);
            boolean sameOrigin = base.getScheme().equalsIgnoreCase(uri.getScheme())
                    && base.getHost() != null && base.getHost().equalsIgnoreCase(uri.getHost())
                    && base.getPort() == uri.getPort()
                    && uri.getRawUserInfo() == null;
            return sameOrigin ? Optional.of(uri) : Optional.empty();
        } catch (java.net.URISyntaxException e) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return "ProxyRoute[pathKey=" + (pathKey == null ? "none" : "***") + ", provider=" + provider
                + ", agentName=" + agentName + ", upstreamPath=" + upstreamPath + "]";
    }
}
