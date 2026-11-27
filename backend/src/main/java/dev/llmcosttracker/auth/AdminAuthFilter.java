package dev.llmcosttracker.auth;

import dev.llmcosttracker.api.ApiErrors;
import java.net.InetSocketAddress;
import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Guards {@code /api/**}:
 * <ul>
 *   <li>{@code POST /api/auth/login} is open but rate limited (5 attempts per minute per IP).</li>
 *   <li>Everything else needs a live admin session cookie, else 401.</li>
 *   <li>State-changing methods also need the session's token in {@code X-CSRF-Token}, else 403.</li>
 * </ul>
 * Every API response is marked {@code Cache-Control: no-store}.
 */
@Component
@Order(-100)
public class AdminAuthFilter implements WebFilter {

    public static final String CSRF_HEADER = "X-CSRF-Token";
    public static final String SESSION_ATTRIBUTE = AdminAuthFilter.class.getName() + ".session";
    static final String LOGIN_PATH = "/api/auth/login";
    private static final Set<HttpMethod> SAFE = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

    private final AdminSessions sessions;
    private final LoginRateLimiter loginLimiter;

    public AdminAuthFilter(AdminSessions sessions, LoginRateLimiter loginLimiter) {
        this.sessions = sessions;
        this.loginLimiter = loginLimiter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().pathWithinApplication().value();
        if (!path.equals("/api") && !path.startsWith("/api/")) {
            return chain.filter(exchange);
        }
        exchange.getResponse().getHeaders().setCacheControl("no-store");

        if (path.equals(LOGIN_PATH) && HttpMethod.POST.equals(request.getMethod())) {
            long retryAfter = loginLimiter.tryAcquire(clientIp(request));
            if (retryAfter > 0) {
                exchange.getResponse().getHeaders().set("Retry-After", Long.toString(retryAfter));
                return ApiErrors.write(exchange.getResponse(), HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                        "Too many sign-in attempts; try again in a minute");
            }
            return chain.filter(exchange);
        }

        HttpCookie cookie = request.getCookies().getFirst(AdminSessions.COOKIE);
        var session = sessions.find(cookie == null ? null : cookie.getValue());
        if (session.isEmpty()) {
            return ApiErrors.write(exchange.getResponse(), HttpStatus.UNAUTHORIZED, "unauthorized", "Sign in first");
        }
        if (!SAFE.contains(request.getMethod())
                && !session.get().csrfMatches(request.getHeaders().getFirst(CSRF_HEADER))) {
            return ApiErrors.write(exchange.getResponse(), HttpStatus.FORBIDDEN, "csrf_token_invalid",
                    "Missing or wrong " + CSRF_HEADER + " header");
        }
        exchange.getAttributes().put(SESSION_ATTRIBUTE, session.get());
        return chain.filter(exchange);
    }

    /**
     * The connecting address. Behind a reverse proxy, set {@code SERVER_FORWARD_HEADERS_STRATEGY=native} so this
     * is the real client (X-Forwarded-For is never trusted by default, since any client can send it).
     */
    static String clientIp(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null) {
            return "unknown";
        }
        return remote.getAddress() != null ? remote.getAddress().getHostAddress() : remote.getHostString();
    }
}
