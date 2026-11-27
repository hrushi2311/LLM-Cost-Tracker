package dev.llmcosttracker.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.llmcosttracker.config.TrackerProperties;
import dev.llmcosttracker.provider.Dialect;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class ProviderRegistryTest {

    private static ProviderRegistry registry(String customProviders) {
        return new ProviderRegistry(new TrackerProperties("test-admin-password", true,
                URI.create("https://api.openai.com/v1"), URI.create("https://api.anthropic.com"), customProviders,
                Duration.ofSeconds(10), Duration.ofMinutes(10), DataSize.ofMegabytes(20), 10_000, true, false, false, true, java.time.ZoneId.of("UTC")));
    }

    @Test
    void builtInProvidersOnlyByDefault() {
        assertThat(registry(null).keys()).containsExactly("openai", "anthropic");
        assertThat(registry("  ").keys()).containsExactly("openai", "anthropic");
    }

    @Test
    void parsesCustomOpenAiCompatibleProviders() {
        ProviderRegistry r = registry(" groq=https://api.groq.com/openai/v1 , local-llm=http://ollama:11434/v1,");

        assertThat(r.keys()).containsExactly("openai", "anthropic", "groq", "local-llm");
        ProviderRegistry.Provider groq = r.find("groq").orElseThrow();
        assertThat(groq.baseUrl()).isEqualTo(URI.create("https://api.groq.com/openai/v1"));
        assertThat(groq.dialect()).isEqualTo(Dialect.OPENAI);
        assertThat(r.find("local-llm").orElseThrow().baseUrl()).isEqualTo(URI.create("http://ollama:11434/v1"));
    }

    @Test
    void rejectsBadConfigurationAtStartup() {
        for (String bad : new String[] {
                "groq",                                    // no url
                "=https://x.example",                      // no name
                "Groq=https://x.example",                  // not a lowercase slug
                "my_llm=https://x.example",                // underscore
                "openai=https://x.example",                // reserved
                "k=https://x.example",                     // reserved for /proxy/k/{key}/
                "a=https://x.example,a=https://y.example", // duplicate
                "a=ftp://x.example",                       // not http(s)
                "a=/relative/path",                        // no host
                "a=https://user:pw@x.example",             // credentials
                "a=https://x.example/v1?key=1",            // query
                "a=https://x.example/v1#frag",             // fragment
                "a=https://x example"}) {                  // invalid URI
            assertThatThrownBy(() -> registry(bad))
                    .as(bad)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("Invalid TRACKER_CUSTOM_PROVIDERS");
        }
    }
}
