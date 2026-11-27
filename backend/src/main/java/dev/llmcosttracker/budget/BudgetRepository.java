package dev.llmcosttracker.budget;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Budget rows and their current-period spend. Alert evaluation, the in-memory spend cache and enforcement come
 * with milestone 7; until then spend is read from {@code usage_event} on demand.
 */
@Repository
public class BudgetRepository {

    private static final String COLUMNS = "id, project_id, agent_id, period, limit_usd, alert_pct, enforce";

    /** Current-period spend for one agent (or, under the null key, for the whole project). */
    public record Spend(BigDecimal day, BigDecimal month) {
        public BigDecimal forPeriod(String period) {
            return period.equals("day") ? day : month;
        }
    }

    private final JdbcTemplate jdbc;

    public BudgetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Budget map(ResultSet rs, int i) throws SQLException {
        return new Budget(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getString(4), rs.getBigDecimal(5), rs.getInt(6), rs.getBoolean(7));
    }

    public List<Budget> list(UUID projectId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM budget WHERE project_id = ? ORDER BY agent_id NULLS FIRST, period",
                BudgetRepository::map, projectId);
    }

    public Optional<Budget> find(UUID projectId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM budget WHERE project_id = ? AND id = ?",
                BudgetRepository::map, projectId, id).stream().findFirst();
    }

    /** Throws {@code DuplicateKeyException} if the project/agent already has a budget for that period. */
    public Budget insert(Budget b) {
        jdbc.update("INSERT INTO budget (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)",
                b.id(), b.projectId(), b.agentId(), b.period(), b.limitUsd(), b.alertPct(), b.enforce());
        return b;
    }

    public boolean update(Budget b) {
        return jdbc.update("UPDATE budget SET agent_id = ?, period = ?, limit_usd = ?, alert_pct = ?, enforce = ? "
                        + "WHERE project_id = ? AND id = ?",
                b.agentId(), b.period(), b.limitUsd(), b.alertPct(), b.enforce(), b.projectId(), b.id()) > 0;
    }

    public boolean delete(UUID projectId, UUID id) {
        return jdbc.update("DELETE FROM budget WHERE project_id = ? AND id = ?", projectId, id) > 0;
    }

    /**
     * Spend in the current day and month for every agent with events this month, plus the project total under the
     * {@code null} key. One range scan from the start of the month.
     */
    public Map<UUID, Spend> currentSpend(UUID projectId, Instant now, ZoneId zone) {
        Instant monthStart = BudgetPeriod.containing("month", now, zone).start();
        Instant dayStart = BudgetPeriod.containing("day", now, zone).start();
        Map<UUID, Spend> out = new HashMap<>();
        BigDecimal[] total = {BigDecimal.ZERO, BigDecimal.ZERO};
        jdbc.query("SELECT agent_id, COALESCE(SUM(cost_usd) FILTER (WHERE created_at >= ?), 0), "
                        + "COALESCE(SUM(cost_usd), 0) FROM usage_event "
                        + "WHERE project_id = ? AND created_at >= ? GROUP BY agent_id",
                rs -> {
                    BigDecimal day = rs.getBigDecimal(2);
                    BigDecimal month = rs.getBigDecimal(3);
                    UUID agent = rs.getObject(1, UUID.class);
                    if (agent != null) {
                        out.put(agent, new Spend(day, month));
                    }
                    total[0] = total[0].add(day);
                    total[1] = total[1].add(month);
                },
                dayStart.atOffset(ZoneOffset.UTC), projectId, monthStart.atOffset(ZoneOffset.UTC));
        out.put(null, new Spend(total[0], total[1]));
        return out;
    }

    public static final Spend NONE = new Spend(BigDecimal.ZERO, BigDecimal.ZERO);
}
