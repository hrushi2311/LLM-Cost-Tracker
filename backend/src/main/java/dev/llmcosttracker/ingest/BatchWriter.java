package dev.llmcosttracker.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.llmcosttracker.attribution.AgentRef;
import dev.llmcosttracker.attribution.AgentRepository;
import dev.llmcosttracker.pricing.CostCalculator;
import dev.llmcosttracker.pricing.PriceRepository;
import dev.llmcosttracker.pricing.PriceTable;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Single writer thread: drains the queue and batch-inserts every second or every 500 events, whichever comes
 * first. Prices each event from the price row in effect on its (UTC) date. If the database is down the batch is
 * dropped and counted; nothing here ever reaches back into the request path.
 */
@Component
public class BatchWriter implements SmartLifecycle {

    static final int BATCH_SIZE = 500;
    static final long BATCH_INTERVAL_MS = 1_000;

    private static final Logger log = LoggerFactory.getLogger(BatchWriter.class);
    private static final String INSERT = "INSERT INTO usage_event (project_id, agent_id, run_id, provider, endpoint, "
            + "model, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, reasoning_tokens, cost_usd, "
            + "streamed, incomplete, status_code, latency_ms, tags, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final UsageEventQueue queue;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PriceRepository prices;
    private final AgentRepository agents;
    private volatile boolean running;
    private Thread thread;

    public BatchWriter(UsageEventQueue queue, JdbcTemplate jdbc, TransactionTemplate tx, PriceRepository prices,
            AgentRepository agents) {
        this.queue = queue;
        this.jdbc = jdbc;
        this.tx = tx;
        this.prices = prices;
        this.agents = agents;
    }

    @Override
    public synchronized void start() {
        running = true;
        thread = Thread.ofPlatform().name("usage-batch-writer").daemon(true).start(this::loop);
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running || queue.depth() > 0) {
            try {
                List<UsageEvent> batch = nextBatch();
                if (!batch.isEmpty()) {
                    write(batch);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.error("Unexpected error in usage batch writer", e);
            }
        }
    }

    private List<UsageEvent> nextBatch() throws InterruptedException {
        List<UsageEvent> batch = new ArrayList<>(BATCH_SIZE);
        UsageEvent first = queue.poll(200, TimeUnit.MILLISECONDS);
        if (first == null) {
            return batch;
        }
        batch.add(first);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BATCH_INTERVAL_MS);
        while (batch.size() < BATCH_SIZE && running) {
            queue.drainTo(batch, BATCH_SIZE - batch.size());
            long remaining = deadline - System.nanoTime();
            if (batch.size() >= BATCH_SIZE || remaining <= 0) {
                break;
            }
            UsageEvent next = queue.poll(remaining, TimeUnit.NANOSECONDS);
            if (next != null) {
                batch.add(next);
            }
        }
        return batch;
    }

    private record AgentKey(UUID projectId, AgentRef ref) {}

    /**
     * One transaction per batch: resolve each distinct agent reference (alias first, then upsert), insert the
     * priced events, and move each agent's last_seen forward.
     */
    void write(List<UsageEvent> batch) {
        try {
            PriceTable table = prices.load();
            int[] types = {Types.OTHER, Types.OTHER, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
                    Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.NUMERIC,
                    Types.BOOLEAN, Types.BOOLEAN, Types.INTEGER, Types.INTEGER, Types.VARCHAR,
                    Types.TIMESTAMP_WITH_TIMEZONE};
            tx.executeWithoutResult(s -> {
                Map<AgentKey, UUID> agentIds = new HashMap<>();
                Map<UUID, Instant> lastSeen = new HashMap<>();
                List<Object[]> rows = new ArrayList<>(batch.size());
                for (UsageEvent e : batch) {
                    UUID agentId = agentIds.computeIfAbsent(new AgentKey(e.projectId(), e.agent()),
                            k -> agents.resolve(k.projectId(), k.ref(), e.createdAt()));
                    lastSeen.merge(agentId, e.createdAt(), (a, b) -> a.isAfter(b) ? a : b);
                    rows.add(toRow(e, agentId, cost(table, e)));
                }
                jdbc.batchUpdate(INSERT, rows, types);
                agents.touch(lastSeen);
            });
        } catch (RuntimeException ex) {
            queue.recordDropped(batch.size(), "database unavailable (" + ex.getClass().getSimpleName() + ")");
        }
    }

    static BigDecimal cost(PriceTable table, UsageEvent e) {
        LocalDate date = LocalDate.ofInstant(e.createdAt(), ZoneOffset.UTC);
        return table.find(e.provider(), e.model(), date)
                .map(p -> CostCalculator.cost(e.usage(), p))
                .orElse(null);
    }

    private static Object[] toRow(UsageEvent e, UUID agentId, BigDecimal cost) {
        return new Object[] {
            e.projectId(), agentId, e.runId(), e.provider(), e.endpoint(), e.model(),
            e.usage().inputTokens(), e.usage().outputTokens(), e.usage().cacheReadTokens(),
            e.usage().cacheWriteTokens(), e.usage().reasoningTokens(), cost,
            e.streamed(), e.incomplete(), e.statusCode(), e.latencyMs(), tagsJson(e.tags()),
            e.createdAt().atOffset(ZoneOffset.UTC)
        };
    }

    private static String tagsJson(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(tags);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
