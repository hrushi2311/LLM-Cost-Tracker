package dev.llmcosttracker.config;

import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;

/** Logs a warning at most once per minute, so a failing dependency cannot flood the log. */
public final class RateLimitedLog {

    private static final long INTERVAL_NANOS = 60_000_000_000L;

    private final Logger log;
    private final AtomicLong lastWarn = new AtomicLong(System.nanoTime() - INTERVAL_NANOS);

    public RateLimitedLog(Logger log) {
        this.log = log;
    }

    public void warn(String format, Object... args) {
        long now = System.nanoTime();
        long last = lastWarn.get();
        if (now - last >= INTERVAL_NANOS && lastWarn.compareAndSet(last, now)) {
            log.warn(format, args);
        }
    }
}
