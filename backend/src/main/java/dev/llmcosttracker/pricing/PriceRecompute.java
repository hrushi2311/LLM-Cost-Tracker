package dev.llmcosttracker.pricing;

import dev.llmcosttracker.provider.Usage;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Re-prices events in batches of 5,000, walking the primary key so each batch is a short transaction and the walk
 * always ends. Default: only events with {@code cost_usd IS NULL}. With {@code all}, every event in the given range
 * (for example after correcting a wrong price row). One recompute runs at a time.
 */
@Service
public class PriceRecompute {

    public static final int BATCH = 5_000;

    public record Result(long examined, long updated, long stillUnpriced) {}

    private record Row(long id, String provider, String model, Usage usage, Instant createdAt) {}

    private final JdbcTemplate jdbc;
    private final PriceRepository prices;
    private final AtomicBoolean running = new AtomicBoolean();

    public PriceRecompute(JdbcTemplate jdbc, PriceRepository prices) {
        this.jdbc = jdbc;
        this.prices = prices;
    }

    /** Empty when another recompute is already running. */
    public java.util.Optional<Result> run(boolean all, Instant from, Instant to) {
        if (!running.compareAndSet(false, true)) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(doRun(all, from, to));
        } finally {
            running.set(false);
        }
    }

    private Result doRun(boolean all, Instant from, Instant to) {
        PriceTable table = prices.load();
        String where = all ? "created_at >= ? AND created_at < ?" : "cost_usd IS NULL";
        long lastId = 0;
        long examined = 0;
        long updated = 0;
        long stillUnpriced = 0;
        while (true) {
            List<Object> args = new ArrayList<>();
            if (all) {
                args.add(from.atOffset(ZoneOffset.UTC));
                args.add(to.atOffset(ZoneOffset.UTC));
            }
            args.add(lastId);
            args.add(BATCH);
            List<Row> rows = jdbc.query("SELECT id, provider, model, input_tokens, output_tokens, cache_read_tokens, "
                            + "cache_write_tokens, reasoning_tokens, created_at FROM usage_event WHERE " + where
                            + " AND id > ? ORDER BY id LIMIT ?",
                    (rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3),
                            new Usage(rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7),
                                    rs.getInt(8)),
                            rs.getObject(9, OffsetDateTime.class).toInstant()),
                    args.toArray());
            if (rows.isEmpty()) {
                break;
            }
            List<Object[]> updates = new ArrayList<>();
            for (Row r : rows) {
                BigDecimal cost = table.find(r.provider(), r.model(), LocalDate.ofInstant(r.createdAt(), ZoneOffset.UTC))
                        .map(p -> CostCalculator.cost(r.usage(), p))
                        .orElse(null);
                if (cost == null) {
                    stillUnpriced++;
                }
                if (cost != null || all) {
                    updates.add(new Object[] {cost, r.id()});
                }
            }
            if (!updates.isEmpty()) {
                int[][] counts = jdbc.batchUpdate("UPDATE usage_event SET cost_usd = ? WHERE id = ?", updates,
                        updates.size(), (ps, u) -> {
                            ps.setObject(1, u[0], Types.NUMERIC);
                            ps.setLong(2, (Long) u[1]);
                        });
                for (int[] batch : counts) {
                    for (int c : batch) {
                        updated += Math.max(c, 0);
                    }
                }
            }
            examined += rows.size();
            lastId = rows.get(rows.size() - 1).id();
        }
        return new Result(examined, updated, stillUnpriced);
    }
}
