package dev.llmcosttracker.demo;

import dev.llmcosttracker.auth.ProjectRepository;
import dev.llmcosttracker.config.TrackerProperties;
import dev.llmcosttracker.pricing.PriceRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes a demo project straight into the database (not through the proxy queue): project, agents, events and a
 * few budgets. Used by {@code --seed-demo} and by the performance check. Never runs on its own.
 */
@Service
public class DemoSeeder {

    /** Rows per multi-row INSERT; 18 columns × 1,000 rows stays under Postgres's 65,535 bind-parameter limit. */
    static final int ROWS_PER_INSERT = 1_000;
    private static final String COLUMNS = "(project_id, agent_id, run_id, provider, endpoint, model, input_tokens, "
            + "output_tokens, cache_read_tokens, cache_write_tokens, reasoning_tokens, cost_usd, streamed, incomplete, "
            + "status_code, latency_ms, tags, created_at)";
    private static final String ROW = "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?::jsonb, ?)";

    public record Result(UUID projectId, String projectName, String key, long events, long runs, Instant from,
            Instant to) {}

    private final ProjectRepository projects;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PriceRepository prices;
    private final Clock clock;
    private final TrackerProperties props;

    public DemoSeeder(ProjectRepository projects, JdbcTemplate jdbc, TransactionTemplate tx, PriceRepository prices,
            Clock clock, TrackerProperties props) {
        this.projects = projects;
        this.jdbc = jdbc;
        this.tx = tx;
        this.prices = prices;
        this.clock = clock;
        this.props = props;
    }

    /** Throws {@code DuplicateKeyException} if a project with that name exists. */
    public Result seed(String projectName, int events, int days, long randomSeed) {
        Result result = tx.execute(s -> write(projectName, events, days, randomSeed));
        jdbc.execute("ANALYZE usage_event");
        return result;
    }

    /** Everything in one transaction, so a failed seed leaves no half-filled project behind. */
    private Result write(String projectName, int events, int days, long randomSeed) {
        ProjectRepository.CreatedProject project = projects.create(projectName);
        Instant now = clock.instant();
        DemoData data = new DemoData(randomSeed, now, days, props.timezone(), prices.load());

        List<UUID> agentIds = DemoData.newIds(DemoData.AGENTS.size());
        Instant[] firstSeen = new Instant[agentIds.size()];
        Instant[] lastSeen = new Instant[agentIds.size()];
        long[] count = {0};
        java.util.Set<String> runs = new java.util.HashSet<>();
        List<DemoData.Event> pending = new ArrayList<>(ROWS_PER_INSERT);

        {
            for (int i = 0; i < agentIds.size(); i++) {
                DemoData.AgentSpec a = DemoData.AGENTS.get(i);
                jdbc.update("INSERT INTO agent (id, project_id, name, source, fingerprint, first_seen, last_seen) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                        agentIds.get(i), project.id(), a.name(), a.source(), a.fingerprint(),
                        now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC));
            }
            data.generate(events, e -> {
                pending.add(e);
                count[0]++;
                if (e.runId() != null) {
                    runs.add(e.runId());
                }
                Instant at = e.createdAt();
                if (firstSeen[e.agent()] == null || at.isBefore(firstSeen[e.agent()])) {
                    firstSeen[e.agent()] = at;
                }
                if (lastSeen[e.agent()] == null || at.isAfter(lastSeen[e.agent()])) {
                    lastSeen[e.agent()] = at;
                }
                if (pending.size() == ROWS_PER_INSERT) {
                    insert(project.id(), agentIds, pending);
                    pending.clear();
                }
            });
            insert(project.id(), agentIds, pending);
            for (int i = 0; i < agentIds.size(); i++) {
                if (firstSeen[i] != null) {
                    jdbc.update("UPDATE agent SET first_seen = ?, last_seen = ? WHERE id = ?",
                            firstSeen[i].atOffset(ZoneOffset.UTC), lastSeen[i].atOffset(ZoneOffset.UTC), agentIds.get(i));
                }
            }
            addBudgets(project.id(), agentIds, data);
        }
        return new Result(project.id(), project.name(), project.key(), count[0], runs.size(), data.start(), now);
    }

    private void insert(UUID projectId, List<UUID> agentIds, List<DemoData.Event> rows) {
        if (rows.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder("INSERT INTO usage_event ").append(COLUMNS).append(" VALUES ");
        List<Object> args = new ArrayList<>(rows.size() * 17);
        for (int i = 0; i < rows.size(); i++) {
            DemoData.Event e = rows.get(i);
            sql.append(i == 0 ? "" : ",").append(ROW);
            args.addAll(Arrays.asList(projectId, agentIds.get(e.agent()), e.runId(), e.provider(), e.endpoint(),
                    e.model(), e.inputTokens(), e.outputTokens(), e.cacheReadTokens(), e.cacheWriteTokens(),
                    e.costUsd(), e.streamed(), e.incomplete(), e.statusCode(), e.latencyMs(), e.tagsJson(),
                    e.createdAt().atOffset(ZoneOffset.UTC)));
        }
        jdbc.update(sql.toString(), args.toArray());
    }

    /**
     * A monthly project budget and monthly budgets for the three named agents, sized from the last 30 days so the
     * bars look like a real month in progress. The fingerprint agent has none ("No budget set").
     */
    private void addBudgets(UUID projectId, List<UUID> agentIds, DemoData data) {
        List<BigDecimal> spend = new ArrayList<>();
        for (UUID agent : agentIds) {
            spend.add(jdbc.queryForObject("SELECT COALESCE(SUM(cost_usd), 0) FROM usage_event WHERE agent_id = ?",
                    BigDecimal.class, agent));
        }
        BigDecimal total = spend.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal perMonth = total.multiply(BigDecimal.valueOf(30))
                .divide(BigDecimal.valueOf(Math.max(1, data.days())), 2, java.math.RoundingMode.HALF_UP);
        insertBudget(projectId, null, DemoData.roundBudget(perMonth.multiply(new BigDecimal("1.15"))), 80);
        double[] headroom = {1.05, 1.4, 1.6};
        for (int i = 0; i < 3; i++) {
            BigDecimal agentMonth = spend.get(i).multiply(BigDecimal.valueOf(30))
                    .divide(BigDecimal.valueOf(Math.max(1, data.days())), 2, java.math.RoundingMode.HALF_UP);
            insertBudget(projectId, agentIds.get(i),
                    DemoData.roundBudget(agentMonth.multiply(BigDecimal.valueOf(headroom[i]))), 80);
        }
    }

    private void insertBudget(UUID projectId, UUID agentId, BigDecimal limit, int alertPct) {
        jdbc.update("INSERT INTO budget (id, project_id, agent_id, period, limit_usd, alert_pct) "
                + "VALUES (?, ?, ?, 'month', ?, ?)", UUID.randomUUID(), projectId, agentId, limit, alertPct);
    }
}
