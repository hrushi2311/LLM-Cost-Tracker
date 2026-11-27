package dev.llmcosttracker.api;

import dev.llmcosttracker.PostgresIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Dashboard API tests: one Spring context, a real Postgres, a {@link TestClock} the tests can move forward, and a
 * signed-in admin per test. Data is inserted with SQL so every expected figure can be worked out by hand.
 */
@AutoConfigureWebTestClient(timeout = "PT60S")
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "tracker.admin-password=" + ApiTestBase.PASSWORD,
            "tracker.timezone=Asia/Kolkata",
            // No upstream calls are expected from API tests; point OpenAI at a closed port just in case.
            "tracker.openai-base-url=http://127.0.0.1:9/v1",
            // Loggers most likely to print requests, so "never logged" checks are meaningful.
            "logging.level.org.springframework.web=DEBUG",
            "logging.level.reactor.netty.http=DEBUG"
        })
@Import(ApiTestBase.ClockConfig.class)
public abstract class ApiTestBase extends PostgresIntegrationTest {

    static final String PASSWORD = "correct-horse-battery-staple";
    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    /** A clock that only moves when a test says so. */
    public static final class TestClock extends Clock {
        private volatile Instant now = Instant.now();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        public void advance(Duration d) {
            now = now.plus(d);
        }

        public void set(Instant instant) {
            now = instant;
        }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }
    }

    @Autowired
    protected WebTestClient web;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected TestClock clock;

    /** Session cookie and CSRF token of the admin signed in for this test. */
    protected String sessionCookie;
    protected String csrf;

    /** "Now" for every test in the class; null keeps the clock running on from wherever it is. */
    protected Instant testNow() {
        return null;
    }

    @BeforeEach
    void cleanAndSignIn() {
        jdbc.update("DELETE FROM project"); // cascades to agents, events, budgets, alerts
        jdbc.update("DELETE FROM model_price");
        if (testNow() == null) {
            signIn();
            return;
        }
        // Sign in a little earlier each time, so earlier sign-ins look like the future to the login rate limiter
        // (which drops them) instead of piling up in its one-minute window; then jump to the class's "now".
        clock.set(testNow().minus(Duration.ofHours(1)).minus(Duration.ofMinutes(2L * signIns++)));
        signIn();
        clock.set(testNow());
    }

    private static int signIns;

    /** Moves past the login rate-limit window first, so any number of tests can sign in. */
    protected void signIn() {
        clock.advance(Duration.ofSeconds(61));
        var result = web.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("password", PASSWORD))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult();
        ResponseCookie cookie = result.getResponseCookies().getFirst("tracker_session");
        sessionCookie = cookie.getValue();
        csrf = (String) result.getResponseBody().get("csrfToken");
    }

    protected WebTestClient.RequestHeadersSpec<?> get(String uri, Object... vars) {
        return web.get().uri(uri, vars).cookie("tracker_session", sessionCookie);
    }

    protected WebTestClient.RequestBodySpec post(String uri, Object... vars) {
        return web.post().uri(uri, vars).cookie("tracker_session", sessionCookie).header("X-CSRF-Token", csrf);
    }

    protected WebTestClient.RequestBodySpec put(String uri, Object... vars) {
        return web.put().uri(uri, vars).cookie("tracker_session", sessionCookie).header("X-CSRF-Token", csrf);
    }

    protected WebTestClient.RequestBodySpec patch(String uri, Object... vars) {
        return web.patch().uri(uri, vars).cookie("tracker_session", sessionCookie).header("X-CSRF-Token", csrf);
    }

    protected WebTestClient.RequestHeadersSpec<?> delete(String uri, Object... vars) {
        return web.delete().uri(uri, vars).cookie("tracker_session", sessionCookie).header("X-CSRF-Token", csrf);
    }

    // --- data helpers --------------------------------------------------------------------------------------------

    protected UUID project(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO project (id, name, key_hash, key_last4) VALUES (?, ?, ?, 'abcd')", id, name,
                "hash-" + id);
        return id;
    }

    protected UUID agent(UUID projectId, String name, String source) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO agent (id, project_id, name, source, fingerprint) VALUES (?, ?, ?, ?, ?)", id,
                projectId, name, source, source.equals("fingerprint") ? "f".repeat(64) : null);
        return id;
    }

    /** One event row. {@code cost} null = unpriced. */
    protected void event(UUID projectId, UUID agentId, String runId, String provider, String model, int in, int out,
            int cacheRead, String cost, int status, boolean incomplete, int latencyMs, Instant at) {
        jdbc.update("INSERT INTO usage_event (project_id, agent_id, run_id, provider, endpoint, model, input_tokens, "
                        + "output_tokens, cache_read_tokens, cache_write_tokens, reasoning_tokens, cost_usd, streamed, "
                        + "incomplete, status_code, latency_ms, created_at) "
                        + "VALUES (?, ?, ?, ?, 'chat.completions', ?, ?, ?, ?, 0, 0, ?::numeric, ?, ?, ?, ?, ?)",
                projectId, agentId, runId, provider, model, in, out, cacheRead, cost, incomplete, incomplete, status,
                latencyMs, at.atOffset(ZoneOffset.UTC));
    }

    protected static List<String> errorShape() {
        return List.of("$.error.code", "$.error.message");
    }
}
