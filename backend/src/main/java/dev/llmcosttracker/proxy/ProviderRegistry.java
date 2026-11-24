package dev.llmcosttracker.proxy;

import dev.llmcosttracker.config.TrackerProperties;
import dev.llmcosttracker.provider.Dialect;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The only upstreams the proxy will ever call. Upstream hosts come from configuration, never from request data.
 * Custom OpenAI-compatible providers are added in milestone 3.
 */
@Component
public class ProviderRegistry {

    public record Provider(String key, URI baseUrl, Dialect dialect) {}

    private final Map<String, Provider> providers;

    public ProviderRegistry(TrackerProperties props) {
        this.providers = Map.of(
                "openai", new Provider("openai", props.openaiBaseUrl(), Dialect.OPENAI),
                "anthropic", new Provider("anthropic", props.anthropicBaseUrl(), Dialect.ANTHROPIC));
    }

    public Optional<Provider> find(String key) {
        return Optional.ofNullable(providers.get(key));
    }
}
