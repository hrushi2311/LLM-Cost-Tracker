package dev.llmcosttracker.auth;

import dev.llmcosttracker.api.ApiException;
import dev.llmcosttracker.config.TrackerProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/**
 * Admin sign-in. The session id travels only in an HttpOnly, SameSite=Strict cookie (Secure when the request came
 * over HTTPS); the CSRF token is returned in the body and must be echoed in {@code X-CSRF-Token} on state-changing
 * calls. Login itself accepts only {@code application/json}, which a cross-site form cannot send.
 */
@RestController
@RequestMapping(path = "/api/auth", produces = MediaType.APPLICATION_JSON_VALUE)
public class AuthController {

    /** {@code toString()} never shows the password (Spring logs decoded bodies at DEBUG). */
    public record LoginRequest(String password) {
        @Override
        public String toString() {
            return "LoginRequest[password=***]";
        }
    }

    /** {@code toString()} never shows the token (Spring logs encoded bodies at DEBUG). */
    public record SessionInfo(String csrfToken) {
        @Override
        public String toString() {
            return "SessionInfo[csrfToken=***]";
        }
    }

    private final AdminSessions sessions;
    private final byte[] passwordDigest;

    public AuthController(AdminSessions sessions, TrackerProperties props) {
        this.sessions = sessions;
        this.passwordDigest = sha256(props.adminPassword());
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SessionInfo> login(@RequestBody LoginRequest body, ServerWebExchange exchange) {
        if (body.password() == null || body.password().isEmpty()) {
            throw ApiException.validation("password is required");
        }
        if (body.password().length() > 1024 || !MessageDigest.isEqual(passwordDigest, sha256(body.password()))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Wrong password");
        }
        // Always a fresh session id (no fixation); an old session presented with the login ends here.
        HttpCookie old = exchange.getRequest().getCookies().getFirst(AdminSessions.COOKIE);
        if (old != null) {
            sessions.remove(old.getValue());
        }
        AdminSessions.Created created = sessions.create();
        exchange.getResponse().addCookie(cookie(exchange.getRequest(), created.sessionId(), false));
        return ResponseEntity.ok(new SessionInfo(created.session().csrfToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(ServerWebExchange exchange) {
        HttpCookie cookie = exchange.getRequest().getCookies().getFirst(AdminSessions.COOKIE);
        if (cookie != null) {
            sessions.remove(cookie.getValue());
        }
        exchange.getResponse().addCookie(cookie(exchange.getRequest(), "", true));
        return ResponseEntity.noContent().build();
    }

    /** For a dashboard page reload: the cookie is HttpOnly, so the UI asks for its CSRF token here. */
    @GetMapping("/session")
    public SessionInfo session(ServerWebExchange exchange) {
        AdminSessions.Session session = exchange.getAttribute(AdminAuthFilter.SESSION_ATTRIBUTE);
        return new SessionInfo(session.csrfToken());
    }

    static ResponseCookie cookie(ServerHttpRequest request, String value, boolean expire) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(AdminSessions.COOKIE, value)
                .httpOnly(true)
                .sameSite("Strict")
                .secure("https".equalsIgnoreCase(request.getURI().getScheme()))
                .path("/");
        if (expire) {
            b.maxAge(0);
        }
        return b.build();
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
