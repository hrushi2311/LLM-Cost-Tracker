package dev.llmcosttracker.ingest;

import dev.llmcosttracker.config.RateLimitedLog;
import dev.llmcosttracker.config.TrackerProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collection;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Bounded in-memory queue between the proxy and the batch writer. Offering never blocks: when the queue is full the
 * event is dropped and {@code tracker.events.dropped} is incremented, so the client call is never affected.
 */
@Component
public class UsageEventQueue {

    public static final String DROPPED_METRIC = "tracker.events.dropped";

    private static final Logger log = LoggerFactory.getLogger(UsageEventQueue.class);

    private final BlockingQueue<UsageEvent> queue;
    private final Counter dropped;
    private final RateLimitedLog dropWarnings = new RateLimitedLog(log);

    public UsageEventQueue(TrackerProperties props, MeterRegistry registry) {
        this.queue = new ArrayBlockingQueue<>(props.queueCapacity());
        this.dropped = Counter.builder(DROPPED_METRIC)
                .description("Usage events dropped because the queue was full or the database was unavailable")
                .register(registry);
        Gauge.builder("tracker.queue.depth", queue, BlockingQueue::size).register(registry);
    }

    public boolean offer(UsageEvent event) {
        if (queue.offer(event)) {
            return true;
        }
        recordDropped(1, "queue full");
        return false;
    }

    public void recordDropped(int count, String reason) {
        dropped.increment(count);
        dropWarnings.warn("Dropped {} usage event(s): {} (total dropped: {})", count, reason, (long) dropped.count());
    }

    UsageEvent poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    int drainTo(Collection<UsageEvent> target, int max) {
        return queue.drainTo(target, max);
    }

    public int depth() {
        return queue.size();
    }

    public long droppedCount() {
        return (long) dropped.count();
    }
}
