package dev.llmcosttracker.auth;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * In-memory admin sessions. There is one admin and one app instance, so a map is enough; sessions end on restart
 * and the admin signs in again. Only the SHA-256 of a session id is kept as the map key, and each session has its
 * own random CSRF token.
 */
@Component
public class AdminSessions {

    public static final String COOKIE = "tracker_session";
    static final Duration IDLE_TIMEOUT = Duration.ofHours(12);
    static final Duration MAX_AGE = Duration.ofDays(7);
    static final int MAX_SESSIONS = 1_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** A signed-in admin session. */
    public static final class Session {
        private final String csrfToken;
        private final Instant createdAt;
        private volatile Instant lastSeen;

        Session(String csrfToken, Instant createdAt) {
            this.csrfToken = csrfToken;
            this.createdAt = createdAt;
            this.lastSeen = createdAt;
        }

        public String csrfToken() {
            return csrfToken;
        }

        /** Constant-time check of a CSRF header value. */
        public boolean csrfMatches(String candidate) {
            return candidate != null && MessageDigest.isEqual(
                    csrfToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /** A new session: the id goes into the cookie, the CSRF token to the client. */
    public record Created(String sessionId, Session session) {}

    private final Map<String, Session> byIdHash = new ConcurrentHashMap<>();
    private final Clock clock;

    public AdminSessions(Clock clock) {
        this.clock = clock;
    }

    public Created create() {
        if (byIdHash.size() >= MAX_SESSIONS) {
            purgeExpired();
            // Still full: drop the oldest session rather than refusing to sign in.
            byIdHash.entrySet().stream()
                    .min(Comparator.comparing(e -> e.getValue().createdAt))
                    .ifPresent(e -> byIdHash.remove(e.getKey()));
        }
        String id = randomToken();
        Session session = new Session(randomToken(), clock.instant());
        byIdHash.put(ProjectKeys.hash(id), session);
        return new Created(id, session);
    }

    /** The live session for a cookie value, refreshing its idle timer; empty if unknown or expired. */
    public Optional<Session> find(String sessionId) {
        if (sessionId == null || sessionId.isEmpty() || sessionId.length() > 100) {
            return Optional.empty();
        }
        String key = ProjectKeys.hash(sessionId);
        Session s = byIdHash.get(key);
        if (s == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (expired(s, now)) {
            byIdHash.remove(key);
            return Optional.empty();
        }
        s.lastSeen = now;
        return Optional.of(s);
    }

    public void remove(String sessionId) {
        if (sessionId != null) {
            byIdHash.remove(ProjectKeys.hash(sessionId));
        }
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    public void purgeExpired() {
        Instant now = clock.instant();
        byIdHash.values().removeIf(s -> expired(s, now));
    }

    int size() {
        return byIdHash.size();
    }

    private static boolean expired(Session s, Instant now) {
        return now.isAfter(s.lastSeen.plus(IDLE_TIMEOUT)) || now.isAfter(s.createdAt.plus(MAX_AGE));
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
