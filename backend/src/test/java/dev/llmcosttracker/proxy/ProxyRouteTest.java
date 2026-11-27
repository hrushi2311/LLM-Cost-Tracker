package dev.llmcosttracker.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

class ProxyRouteTest {

    private static final URI OPENAI = URI.create("https://api.openai.com/v1");
    private static final URI ANTHROPIC = URI.create("https://api.anthropic.com");

    @Test
    void parsesProviderAndPath() {
        ProxyRoute r = ProxyRoute.parse("/proxy/openai/chat/completions").orElseThrow();

        assertThat(r).isEqualTo(new ProxyRoute(null, "openai", null, "/chat/completions"));
        assertThat(r.upstreamUri(OPENAI, null)).contains(URI.create("https://api.openai.com/v1/chat/completions"));
    }

    @Test
    void stripsAgentSegment() {
        ProxyRoute r = ProxyRoute.parse("/proxy/openai/agent/planner/chat/completions").orElseThrow();

        assertThat(r.agentName()).isEqualTo("planner");
        assertThat(r.upstreamPath()).isEqualTo("/chat/completions");
    }

    @Test
    void keepsQueryAndEncodingAsSent() {
        ProxyRoute r = ProxyRoute.parse("/proxy/anthropic/v1/models/a%2Fb").orElseThrow();

        assertThat(r.upstreamUri(ANTHROPIC, "limit=2&after=x%20y"))
                .contains(URI.create("https://api.anthropic.com/v1/models/a%2Fb?limit=2&after=x%20y"));
    }

    @Test
    void pathKeyIsParsedAndNeverPartOfTheUpstreamPath() {
        ProxyRoute r = ProxyRoute.parse("/proxy/k/trk_AbC123/openai/agent/planner/chat/completions").orElseThrow();

        assertThat(r.pathKey()).isEqualTo("trk_AbC123");
        assertThat(r.provider()).isEqualTo("openai");
        assertThat(r.agentName()).isEqualTo("planner");
        assertThat(r.upstreamUri(OPENAI, null)).contains(URI.create("https://api.openai.com/v1/chat/completions"));
        assertThat(r.toString()).doesNotContain("trk_AbC123");
    }

    @Test
    void pathKeyNeedsAProviderAfterIt() {
        assertThat(ProxyRoute.parse("/proxy/k/trk_AbC123")).isEmpty();
        assertThat(ProxyRoute.parse("/proxy/k/trk_AbC123/")).isEmpty();
        assertThat(ProxyRoute.parse("/proxy/k//openai/chat/completions")).isEmpty();
    }

    @Test
    void rejectsPathsThatAreNotProxyRoutes() {
        assertThat(ProxyRoute.parse("/api/projects")).isEmpty();
        assertThat(ProxyRoute.parse("/proxy/")).isEmpty();
    }

    @Test
    void requestDataCannotChangeTheUpstreamHost() {
        for (String path : new String[] {
                "/proxy/anthropic//evil.example/x",
                "/proxy/anthropic/@evil.example/x",
                "/proxy/anthropic/..%2F..%2F@evil.example",
                "/proxy/openai/../../evil.example"}) {
            ProxyRoute r = ProxyRoute.parse(path).orElseThrow();
            for (URI base : new URI[] {OPENAI, ANTHROPIC}) {
                r.upstreamUri(base, null).ifPresent(uri -> assertThat(uri.getHost())
                        .as("host for %s", path).isEqualTo(base.getHost()));
            }
        }
    }
}
