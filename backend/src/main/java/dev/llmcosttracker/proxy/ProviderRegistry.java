package dev.llmcosttracker.proxy;

import dev.llmcosttracker.config.TrackerProperties;
import dev.llmcosttracker.provider.Dialect;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The only upstreams the proxy will ever call: OpenAI, Anthropic, and the OpenAI-compatible providers listed in
 * {@code TRACKER_CUSTOM_PROVIDERS}. Upstream hosts come from configuration, never from request data.
 */
@Component
public class ProviderRegistry {

    public record Provider(String key, URI baseUrl, Dialect dialect) {}

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");

    private final Map<String, Provider> providers;

    public ProviderRegistry(TrackerProperties props) {
        Map<String, Provider> all = new LinkedHashMap<>();
        all.put("openai", new Provider("openai", props.openaiBaseUrl(), Dialect.OPENAI));
        all.put("anthropic", new Provider("anthropic", props.anthropicBaseUrl(), Dialect.ANTHROPIC));
        for (Provider custom : parseCustomProviders(props.customProviders())) {
            if (all.putIfAbsent(custom.key(), custom) != null) {
                throw invalid("provider name '" + custom.key() + "' is reserved or listed twice");
            }
        }
        this.providers = Collections.unmodifiableMap(all);
    }

    public Optional<Provider> find(String key) {
        return Optional.ofNullable(providers.get(key));
    }

    public Set<String> keys() {
        return providers.keySet();
    }

    /**
     * Parses {@code name=url[,name=url...]}. Names are lowercase slugs (they appear in the proxy path and in
     * {@code usage_event.provider}); URLs must be absolute http(s) with a host and no credentials, query or fragment.
     * Any mistake fails startup, so a typo never silently routes traffic somewhere unexpected.
     */
    static List<Provider> parseCustomProviders(String spec) {
        List<Provider> out = new ArrayList<>();
        if (spec == null || spec.isBlank()) {
            return out;
        }
        for (String entry : spec.split(",")) {
            String e = entry.trim();
            if (e.isEmpty()) {
                continue;
            }
            int eq = e.indexOf('=');
            if (eq <= 0) {
                throw invalid("expected name=url, got '" + e + "'");
            }
            String name = e.substring(0, eq).trim();
            String url = e.substring(eq + 1).trim();
            if (!NAME.matcher(name).matches()) {
                throw invalid("provider name '" + name + "' must match [a-z0-9][a-z0-9-]{0,31}");
            }
            URI uri;
            try {
                uri = new URI(url);
            } catch (URISyntaxException ex) {
                throw invalid("URL for '" + name + "' is not valid");
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw invalid("URL for '" + name + "' must be an absolute http(s) URL with a host and no "
                        + "credentials, query or fragment");
            }
            out.add(new Provider(name, uri, Dialect.OPENAI));
        }
        return out;
    }

    private static IllegalStateException invalid(String detail) {
        return new IllegalStateException("Invalid TRACKER_CUSTOM_PROVIDERS: " + detail);
    }
}
