package dev.llmcosttracker.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;

/** Login, session cookie, CSRF, logout, and the login rate limit. */
@ExtendWith(OutputCaptureExtension.class)
class AuthApiTest extends ApiTestBase {

    private static final UUID ANY = UUID.fromString("00000000-0000-0000-0000-000000000042");

    /** Every /api endpoint in SPEC.md (plus /api/auth/session), with a body where one is needed. */
    static final List<Object[]> ENDPOINTS = List.of(
            new Object[] {HttpMethod.POST, "/api/auth/logout"},
            new Object[] {HttpMethod.GET, "/api/auth/session"},
            new Object[] {HttpMethod.GET, "/api/projects"},
            new Object[] {HttpMethod.POST, "/api/projects"},
            new Object[] {HttpMethod.POST, "/api/projects/" + ANY + "/rotate-key"},
            new Object[] {HttpMethod.GET, "/api/projects/" + ANY + "/summary"},
            new Object[] {HttpMethod.GET, "/api/projects/" + ANY + "/timeseries?groupBy=agent&interval=day"},
            new Object[] {HttpMethod.GET, "/api/projects/" + ANY + "/models"},
            new Object[] {HttpMethod.GET, "/api/projects/" + ANY + "/agents"},
            new Object[] {HttpMethod.PATCH, "/api/agents/" + ANY},
            new Object[] {HttpMethod.POST, "/api/agents/" + ANY + "/merge"},
            new Object[] {HttpMethod.GET, "/api/projects/" + ANY + "/runs"},
            new Object[] {HttpMethod.GET, "/api/projects/" + ANY + "/runs/run_1"},
            new Object[] {HttpMethod.GET, "/api/projects/" + ANY + "/budgets"},
            new Object[] {HttpMethod.POST, "/api/projects/" + ANY + "/budgets"},
            new Object[] {HttpMethod.GET, "/api/projects/" + ANY + "/budgets/" + ANY},
            new Object[] {HttpMethod.PUT, "/api/projects/" + ANY + "/budgets/" + ANY},
            new Object[] {HttpMethod.DELETE, "/api/projects/" + ANY + "/budgets/" + ANY},
            new Object[] {HttpMethod.GET, "/api/projects/" + ANY + "/alerts"},
            new Object[] {HttpMethod.GET, "/api/prices"},
            new Object[] {HttpMethod.POST, "/api/prices"},
            new Object[] {HttpMethod.POST, "/api/prices/recompute"},
            new Object[] {HttpMethod.GET, "/api/projects/" + ANY + "/events.csv"},
            new Object[] {HttpMethod.GET, "/api/status"},
            new Object[] {HttpMethod.GET, "/api/no-such-endpoint"});

    @Test
    void everyEndpointNeedsASession() {
        for (Object[] e : ENDPOINTS) {
            web.method((HttpMethod) e[0]).uri((String) e[1]).contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                    .exchange()
                    .expectStatus().isUnauthorized()
                    .expectHeader().valueEquals("Cache-Control", "no-store")
                    .expectBody()
                    .jsonPath("$.error.code").isEqualTo("unauthorized")
                    .jsonPath("$.error.message").isNotEmpty();
        }
    }

    @Test
    void anUnknownOrForgedSessionCookieIsRejected() {
        web.get().uri("/api/projects").cookie("tracker_session", "forged-session-id").exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void loginSetsAnHttpOnlyStrictCookieAndReturnsTheCsrfToken() {
        clock.advance(Duration.ofSeconds(61));
        var result = web.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("password", PASSWORD)).exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult();

        ResponseCookie cookie = result.getResponseCookies().getFirst("tracker_session");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Strict");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.isSecure()).isFalse(); // plain HTTP in the test; Secure is set when the request is HTTPS
        assertThat(cookie.getValue()).hasSizeGreaterThanOrEqualTo(43); // 32 random bytes
        String token = (String) result.getResponseBody().get("csrfToken");
        assertThat(token).hasSizeGreaterThanOrEqualTo(43).isNotEqualTo(cookie.getValue());
        assertThat(result.getResponseHeaders().getCacheControl()).isEqualTo("no-store");

        // A page reload gets the same token back from the session.
        web.get().uri("/api/auth/session").cookie("tracker_session", cookie.getValue()).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.csrfToken").isEqualTo(token);
    }

    @Test
    void wrongPasswordIsRejected() {
        clock.advance(Duration.ofSeconds(61));
        web.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("password", "not-the-password")).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().doesNotExist("Set-Cookie")
                .expectBody().jsonPath("$.error.code").isEqualTo("invalid_credentials");
    }

    @Test
    void loginValidatesItsBody() {
        clock.advance(Duration.ofSeconds(61));
        web.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.code").isEqualTo("validation_error");
        web.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("password", PASSWORD, "remember", true)).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.message").isEqualTo("Unknown field 'remember'");
        // A cross-site HTML form can only send form encodings; login accepts JSON only.
        web.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue("password=" + PASSWORD).exchange()
                .expectStatus().isEqualTo(415)
                .expectBody().jsonPath("$.error.code").isEqualTo("unsupported_media_type");
    }

    @Test
    void stateChangingCallsNeedTheCsrfToken() {
        // No token
        web.post().uri("/api/projects").cookie("tracker_session", sessionCookie)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("name", "p1")).exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.error.code").isEqualTo("csrf_token_invalid");
        // Wrong token
        web.post().uri("/api/projects").cookie("tracker_session", sessionCookie).header("X-CSRF-Token", "nope")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("name", "p1")).exchange()
                .expectStatus().isForbidden();
        // Every state-changing method is covered, not just POST.
        for (HttpMethod m : List.of(HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            web.method(m).uri("/api/agents/" + ANY).cookie("tracker_session", sessionCookie)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                    .expectStatus().isForbidden();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project", Long.class)).isZero();
        // Right token
        post("/api/projects").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("name", "p1")).exchange()
                .expectStatus().isCreated();
        // GET needs no token
        get("/api/projects").exchange().expectStatus().isOk();
    }

    @Test
    void logoutEndsTheSession() {
        post("/api/auth/logout").exchange()
                .expectStatus().isNoContent()
                .expectHeader().value("Set-Cookie", v -> assertThat(v).contains("tracker_session=").contains("Max-Age=0"));
        get("/api/projects").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void loginIsLimitedToFiveAttemptsPerMinutePerIp() {
        clock.advance(Duration.ofSeconds(61));
        for (int i = 0; i < 5; i++) {
            web.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("password", "guess-" + i)).exchange()
                    .expectStatus().isUnauthorized();
        }
        // The sixth attempt is refused even with the right password.
        web.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("password", PASSWORD)).exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After")
                .expectBody().jsonPath("$.error.code").isEqualTo("rate_limited");

        clock.advance(Duration.ofSeconds(30));
        web.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("password", PASSWORD)).exchange()
                .expectStatus().isEqualTo(429);

        clock.advance(Duration.ofSeconds(31)); // the window has slid past the first attempts
        web.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("password", PASSWORD)).exchange()
                .expectStatus().isOk();
    }

    @Test
    void sessionsExpireAfterTwelveIdleHours() {
        get("/api/projects").exchange().expectStatus().isOk();
        clock.advance(Duration.ofHours(12).plusSeconds(1));
        get("/api/projects").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void passwordSessionIdAndCsrfTokenNeverReachTheLogs(CapturedOutput output) {
        // Web and Netty loggers run at DEBUG in this context (see ApiTestBase).
        clock.advance(Duration.ofSeconds(61));
        web.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("password", "wrong-" + PASSWORD)).exchange().expectStatus().isUnauthorized();
        get("/api/projects").exchange().expectStatus().isOk();
        post("/api/projects").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("name", "logged")).exchange()
                .expectStatus().isCreated();

        assertThat(output.getAll()).doesNotContain(PASSWORD).doesNotContain(sessionCookie).doesNotContain(csrf);
    }

    @Test
    void healthStaysPublic() {
        web.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }
}
