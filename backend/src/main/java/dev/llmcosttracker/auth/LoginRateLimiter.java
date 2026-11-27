package dev.llmcosttracker.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Login rate limit: 5 attempts per minute per client IP, as a sliding window. Every attempt counts, successful or
 * not. State is in memory and bounded: idle entries are purged, and the map is cleared if it ever grows past
 * {@link #MAX_TRACKED_IPS} (an attacker spraying source addresses gets nothing from that).
 */
@Component
public class LoginRateLimiter {

    static final int MAX_ATTEMPTS = 5;
    static final Duration WINDOW = Duration.ofMinutes(1);
    static final int MAX_TRACKED_IPS = 100_000;

    private final Map<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Records an attempt; returns 0 if allowed, otherwise the seconds until the next attempt is allowed. */
    public long tryAcquire(String ip) {
        if (attempts.size() > MAX_TRACKED_IPS) {
            purge();
            if (attempts.size() > MAX_TRACKED_IPS) {
                attempts.clear(); // keep memory bounded; needs 100k distinct source IPs inside one minute
            }
        }
        Instant now = clock.instant();
        Deque<Instant> window = attempts.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (window) {
            Instant cutoff = now.minus(WINDOW);
            window.removeIf(t -> t.isAfter(now)); // the clock stepped back (e.g. NTP): don't lock anyone out
            while (!window.isEmpty() && !window.peekFirst().isAfter(cutoff)) {
                window.pollFirst();
            }
            if (window.size() >= MAX_ATTEMPTS) {
                long wait = Duration.between(cutoff, window.peekFirst()).toSeconds() + 1;
                return Math.max(1, wait);
            }
            window.addLast(now);
            return 0;
        }
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void purge() {
        Instant cutoff = clock.instant().minus(WINDOW);
        attempts.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return e.getValue().isEmpty() || !e.getValue().peekLast().isAfter(cutoff);
            }
        });
    }
}
