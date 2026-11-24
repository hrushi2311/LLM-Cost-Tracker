package dev.llmcosttracker;

import java.time.Duration;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Minimal polling helper for asynchronous results (avoids adding a dependency for it). */
public final class Await {

    private Await() {}

    public static <T> T until(Supplier<T> supplier, Predicate<T> done, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        T value = supplier.get();
        while (!done.test(value)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + timeout + "; last value: " + value);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting", e);
            }
            value = supplier.get();
        }
        return value;
    }
}
