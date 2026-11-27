package dev.llmcosttracker.api;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only aggregates over {@code usage_event} for the dashboard.
 *
 * <p>Performance rules (1M events must stay well under 500 ms per endpoint):
 * <ul>
 *   <li>Each endpoint makes one pass over its rows, bounded by project and time so it uses
 *       {@code ue_project_time}. Different time windows (range, previous period, budget month and day) are tagged
 *       with boolean group keys in that same pass rather than scanned again.</li>
 *   <li>Groups stay tiny (agents × models × flags); Java adds them up. No {@code DISTINCT} aggregates over the
 *       range, which would sort every row.</li>
 *   <li>Time buckets are 15-minute UTC slots ({@code epoch / 900}, cheap integer math); Java maps each slot to its
 *       hour or day in {@code TRACKER_TIMEZONE}. Every zone in use today is offset from UTC by a multiple of 15
 *       minutes, so a slot never straddles a local hour or day boundary.</li>
 *   <li>Runs are found by walking the newest events backwards and only the runs on the page are aggregated.</li>
 * </ul>
 * Unpriced events (null cost) count as zero cost and are reported separately.
 */
@Repository
public class DashboardQueries {

    static final int SLOT_SECONDS = 900;

    private final JdbcTemplate jdbc;

    public DashboardQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static OffsetDateTime ts(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }

    // --- one pass, several windows -------------------------------------------------------------------------------

    /**
     * Windows one scan tags rows with. {@code previous}, {@code monthStart} and {@code dayStart} may be null.
     * Budget windows have no upper bound (they run to now).
     */
    public record Windows(TimeRange range, TimeRange previous, Instant monthStart, Instant dayStart) {

        Instant lowerBound() {
            Instant lo = range.from();
            for (Instant i : new Instant[] {previous == null ? null : previous.from(), monthStart, dayStart}) {
                if (i != null && i.isBefore(lo)) {
                    lo = i;
                }
            }
            return lo;
        }

        boolean openEnded() {
            return monthStart != null || dayStart != null;
        }
    }

    /** Sums for one group of rows. */
    public record Stats(long requests, BigDecimal costUsd, long unpriced, long inputTokens, long outputTokens,
            long cacheReadTokens, long cacheWriteTokens, long reasoningTokens, long errors, long incomplete,
            BigDecimal runCostUsd) {

        public static final Stats ZERO = new Stats(0, BigDecimal.ZERO, 0, 0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO);

        public Stats plus(Stats o) {
            return new Stats(requests + o.requests, costUsd.add(o.costUsd), unpriced + o.unpriced,
                    inputTokens + o.inputTokens, outputTokens + o.outputTokens, cacheReadTokens + o.cacheReadTokens,
                    cacheWriteTokens + o.cacheWriteTokens, reasoningTokens + o.reasoningTokens, errors + o.errors,
                    incomplete + o.incomplete, runCostUsd.add(o.runCostUsd));
        }
    }

    /** One group: an agent (and, if asked, a provider and model) and the windows its rows fall in. */
    public record Slice(UUID agentId, String provider, String model, boolean inRange, boolean inPrevious,
            boolean inMonth, boolean inDay, Stats stats) {}

    /** SQL text and its arguments, so the performance check can EXPLAIN exactly what runs. */
    public record Query(String sql, Object[] args) {}

    public List<Slice> slices(UUID projectId, Windows w, boolean byModel) {
        Query q = slicesQuery(projectId, w, byModel);
        return jdbc.query(q.sql(), (rs, i) -> new Slice(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getBoolean(4), rs.getBoolean(5), rs.getBoolean(6), rs.getBoolean(7),
                new Stats(rs.getLong(8), rs.getBigDecimal(9), rs.getLong(10), rs.getLong(11), rs.getLong(12),
                        rs.getLong(13), rs.getLong(14), rs.getLong(15), rs.getLong(16), rs.getLong(17),
                        rs.getBigDecimal(18))),
                q.args());
    }

    public Query slicesQuery(UUID projectId, Windows w, boolean byModel) {
        List<Object> args = new ArrayList<>();
        String inRange = "(created_at >= ? AND created_at < ?)";
        args.add(ts(w.range().from()));
        args.add(ts(w.range().to()));
        String inPrevious = "false";
        if (w.previous() != null) {
            inPrevious = "(created_at >= ? AND created_at < ?)";
            args.add(ts(w.previous().from()));
            args.add(ts(w.previous().to()));
        }
        String inMonth = "false";
        if (w.monthStart() != null) {
            inMonth = "(created_at >= ?)";
            args.add(ts(w.monthStart()));
        }
        String inDay = "false";
        if (w.dayStart() != null) {
            inDay = "(created_at >= ?)";
            args.add(ts(w.dayStart()));
        }
        args.add(projectId);
        args.add(ts(w.lowerBound()));
        String upper = "";
        if (!w.openEnded()) {
            upper = " AND created_at < ?";
            args.add(ts(w.range().to()));
        }
        String modelCols = byModel ? "provider, model" : "NULL::text, NULL::text";
        String groupBy = byModel ? "1, 2, 3, 4, 5, 6, 7" : "1, 4, 5, 6, 7";
        String sql = "SELECT agent_id, " + modelCols + ", " + inRange + ", " + inPrevious + ", " + inMonth + ", " + inDay
                + """
                , count(*), COALESCE(SUM(cost_usd), 0), count(*) FILTER (WHERE cost_usd IS NULL),
                  COALESCE(SUM(input_tokens), 0), COALESCE(SUM(output_tokens), 0),
                  COALESCE(SUM(cache_read_tokens), 0), COALESCE(SUM(cache_write_tokens), 0),
                  COALESCE(SUM(reasoning_tokens), 0), count(*) FILTER (WHERE status_code >= 400),
                  count(*) FILTER (WHERE incomplete), COALESCE(SUM(cost_usd) FILTER (WHERE run_id IS NOT NULL), 0)
                FROM usage_event WHERE project_id = ? AND created_at >= ?""" + upper + " GROUP BY " + groupBy;
        return new Query(sql, args.toArray());
    }

    /** Distinct run ids in the range (a hash aggregate over the run index, not a sort of every row). */
    public long runCount(UUID projectId, TimeRange r) {
        return jdbc.queryForObject("SELECT count(*) FROM (SELECT run_id FROM usage_event WHERE project_id = ? "
                        + "AND run_id IS NOT NULL AND created_at >= ? AND created_at < ? GROUP BY run_id) runs",
                Long.class, projectId, ts(r.from()), ts(r.to()));
    }

    // --- timeseries ----------------------------------------------------------------------------------------------

    /** One 15-minute slot × group. {@code group} is an agent id, or {@code provider + "/" + model}. */
    public record Cell(Instant slot, String group, BigDecimal costUsd, long requests) {}

    public List<Cell> timeseries(UUID projectId, TimeRange r, String groupBy) {
        String groupExpr = groupBy.equals("agent") ? "agent_id::text" : "provider || '/' || COALESCE(model, '')";
        return jdbc.query("SELECT floor(date_part('epoch', created_at) / " + SLOT_SECONDS + ")::bigint, " + groupExpr
                        + ", COALESCE(SUM(cost_usd), 0), count(*) FROM usage_event "
                        + "WHERE project_id = ? AND created_at >= ? AND created_at < ? GROUP BY 1, 2",
                (rs, i) -> new Cell(Instant.ofEpochSecond(rs.getLong(1) * SLOT_SECONDS), rs.getString(2),
                        rs.getBigDecimal(3), rs.getLong(4)),
                projectId, ts(r.from()), ts(r.to()));
    }

    // --- models --------------------------------------------------------------------------------------------------

    public record ModelRow(String provider, String model, long requests, long inputTokens, long outputTokens,
            long cacheReadTokens, long cacheWriteTokens, long reasoningTokens, BigDecimal costUsd,
            long unpricedRequests) {}

    public List<ModelRow> models(UUID projectId, TimeRange r) {
        return jdbc.query("""
                SELECT provider, model, count(*), COALESCE(SUM(input_tokens), 0), COALESCE(SUM(output_tokens), 0),
                       COALESCE(SUM(cache_read_tokens), 0), COALESCE(SUM(cache_write_tokens), 0),
                       COALESCE(SUM(reasoning_tokens), 0), COALESCE(SUM(cost_usd), 0),
                       count(*) FILTER (WHERE cost_usd IS NULL)
                FROM usage_event WHERE project_id = ? AND created_at >= ? AND created_at < ?
                GROUP BY provider, model
                ORDER BY 9 DESC, 3 DESC, provider, model
                """,
                (rs, i) -> new ModelRow(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                        rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getBigDecimal(9), rs.getLong(10)),
                projectId, ts(r.from()), ts(r.to()));
    }

    // --- agents --------------------------------------------------------------------------------------------------

    public record AgentInfo(UUID id, String name, String displayName, String source, Instant firstSeen,
            Instant lastSeen) {}

    public List<AgentInfo> agents(UUID projectId) {
        return jdbc.query("SELECT id, name, display_name, source, first_seen, last_seen FROM agent "
                        + "WHERE project_id = ? ORDER BY name",
                (rs, i) -> new AgentInfo(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getObject(5, OffsetDateTime.class).toInstant(),
                        rs.getObject(6, OffsetDateTime.class).toInstant()),
                projectId);
    }

    // --- runs ----------------------------------------------------------------------------------------------------

    /**
     * A run, derived from its events. An event's {@code created_at} is when the call finished, so a call started at
     * {@code created_at - latency_ms}.
     */
    public record RunRow(String runId, long calls, Instant startedAt, Instant endedAt, BigDecimal costUsd,
            long inputTokens, long outputTokens, long errors, long incomplete, List<UUID> agentIds) {}

    static final int WALK_CHUNK = 1_000;
    private static final Comparator<RunRow> NEWEST_FIRST =
            Comparator.comparing(RunRow::endedAt).thenComparing(RunRow::runId).reversed();

    /**
     * Up to {@code limit} runs newest first (by last activity in the range, then run id), strictly after the cursor
     * when one is given.
     *
     * <p>Walks the range's events from the newest backwards in chunks, collecting run ids, and aggregates only the
     * runs it meets (through {@code ue_run}). It stops once {@code limit} runs are known to end after the walk
     * position: any run not met yet ends at or before that position, so it cannot belong earlier in the order.
     */
    public List<RunRow> runs(UUID projectId, TimeRange r, Instant cursorEnded, String cursorRunId, int limit) {
        Map<String, RunRow> known = new HashMap<>();
        // Walk position: everything at or after (walkAt, walkId) has been read. Starts just past the newest event a
        // page can contain.
        Instant walkAt = r.to();
        if (cursorEnded != null && cursorEnded.plusNanos(1_000).isBefore(walkAt)) {
            walkAt = cursorEnded.plusNanos(1_000); // Postgres keeps microseconds: this includes the cursor's instant
        }
        long walkId = Long.MAX_VALUE;
        boolean exhausted = false;
        while (true) {
            List<Object[]> chunk = jdbc.query("""
                    SELECT run_id, created_at, id FROM usage_event
                    WHERE project_id = ? AND run_id IS NOT NULL AND created_at >= ?
                      AND (created_at < ? OR (created_at = ? AND id < ?))
                    ORDER BY created_at DESC, id DESC LIMIT ?
                    """,
                    (rs, i) -> new Object[] {rs.getString(1), rs.getObject(2, OffsetDateTime.class).toInstant(),
                            rs.getLong(3)},
                    projectId, ts(r.from()), ts(walkAt), ts(walkAt), walkId, WALK_CHUNK);
            Set<String> fresh = new LinkedHashSet<>();
            for (Object[] row : chunk) {
                if (!known.containsKey((String) row[0])) {
                    fresh.add((String) row[0]);
                }
            }
            for (RunRow run : aggregateRuns(projectId, r, fresh)) {
                known.put(run.runId(), run);
            }
            if (chunk.size() < WALK_CHUNK) {
                exhausted = true;
            } else {
                Object[] last = chunk.get(chunk.size() - 1);
                walkAt = (Instant) last[1];
                walkId = (Long) last[2];
            }
            Instant position = walkAt;
            long settled = known.values().stream()
                    .filter(run -> before(run, cursorEnded, cursorRunId) && run.endedAt().isAfter(position))
                    .count();
            if (exhausted || settled >= limit) {
                break;
            }
        }
        return known.values().stream()
                .filter(run -> before(run, cursorEnded, cursorRunId))
                .sorted(NEWEST_FIRST)
                .limit(limit)
                .toList();
    }

    /** True if the run comes after the cursor in newest-first order (or there is no cursor). */
    private static boolean before(RunRow run, Instant cursorEnded, String cursorRunId) {
        if (cursorEnded == null) {
            return true;
        }
        int c = run.endedAt().compareTo(cursorEnded);
        return c < 0 || (c == 0 && run.runId().compareTo(cursorRunId) < 0);
    }

    private List<RunRow> aggregateRuns(UUID projectId, TimeRange r, Set<String> runIds) {
        if (runIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    SELECT run_id, count(*), min(created_at - latency_ms * interval '1 millisecond'), max(created_at),
                           COALESCE(SUM(cost_usd), 0), COALESCE(SUM(input_tokens), 0), COALESCE(SUM(output_tokens), 0),
                           count(*) FILTER (WHERE status_code >= 400), count(*) FILTER (WHERE incomplete),
                           array_agg(DISTINCT agent_id) FILTER (WHERE agent_id IS NOT NULL)
                    FROM usage_event
                    WHERE project_id = ? AND run_id = ANY (?) AND created_at >= ? AND created_at < ?
                    GROUP BY run_id
                    """);
            ps.setObject(1, projectId);
            ps.setArray(2, con.createArrayOf("text", runIds.toArray()));
            ps.setObject(3, ts(r.from()));
            ps.setObject(4, ts(r.to()));
            return ps;
        }, (rs, i) -> {
            Array arr = rs.getArray(10);
            List<UUID> agents = arr == null ? List.of() : List.of((UUID[]) arr.getArray());
            return new RunRow(rs.getString(1), rs.getLong(2), rs.getObject(3, OffsetDateTime.class).toInstant(),
                    rs.getObject(4, OffsetDateTime.class).toInstant(), rs.getBigDecimal(5), rs.getLong(6),
                    rs.getLong(7), rs.getLong(8), rs.getLong(9), agents);
        });
    }

    public record RunEvent(long id, UUID agentId, String provider, String endpoint, String model, long inputTokens,
            long outputTokens, long cacheReadTokens, long cacheWriteTokens, long reasoningTokens, BigDecimal costUsd,
            boolean streamed, boolean incomplete, int statusCode, int latencyMs, Instant startedAt,
            Instant endedAt) {}

    /** The run's events in start order; at most {@code limit}. */
    public List<RunEvent> runEvents(UUID projectId, String runId, int limit) {
        return jdbc.query("""
                SELECT id, agent_id, provider, endpoint, model, input_tokens, output_tokens, cache_read_tokens,
                       cache_write_tokens, reasoning_tokens, cost_usd, streamed, incomplete, status_code, latency_ms,
                       created_at - latency_ms * interval '1 millisecond' AS started_at, created_at
                FROM usage_event WHERE project_id = ? AND run_id = ?
                ORDER BY started_at, id LIMIT ?
                """,
                (rs, i) -> new RunEvent(rs.getLong(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getLong(10),
                        rs.getBigDecimal(11), rs.getBoolean(12), rs.getBoolean(13), rs.getInt(14), rs.getInt(15),
                        rs.getObject(16, OffsetDateTime.class).toInstant(),
                        rs.getObject(17, OffsetDateTime.class).toInstant()),
                projectId, runId, limit);
    }

    // --- status --------------------------------------------------------------------------------------------------

    /** Newest event overall: by id (the primary key), so no extra index is needed. */
    public Instant lastEventAt() {
        return jdbc.query("SELECT created_at FROM usage_event ORDER BY id DESC LIMIT 1",
                (rs, i) -> rs.getObject(1, OffsetDateTime.class).toInstant()).stream().findFirst().orElse(null);
    }

    public Instant lastEventAt(UUID projectId) {
        return jdbc.query("SELECT created_at FROM usage_event WHERE project_id = ? ORDER BY created_at DESC LIMIT 1",
                (rs, i) -> rs.getObject(1, OffsetDateTime.class).toInstant(), projectId)
                .stream().findFirst().orElse(null);
    }
}
