package dev.llmcosttracker.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** The project's 100 most recent alert events. Alerts are written by budget evaluation (milestone 7). */
@RestController
@RequestMapping(path = "/api/projects/{projectId}/alerts", produces = MediaType.APPLICATION_JSON_VALUE)
public class AlertsController {

    static final int LIMIT = 100;

    public record AlertView(long id, UUID budgetId, UUID agentId, String agentName, String period,
            LocalDate periodStart, String kind, String spentUsd, String limitUsd, Instant createdAt) {}

    private final JdbcTemplate jdbc;
    private final DashboardController dashboard;

    public AlertsController(JdbcTemplate jdbc, DashboardController dashboard) {
        this.jdbc = jdbc;
        this.dashboard = dashboard;
    }

    @GetMapping
    public Mono<List<AlertView>> list(@PathVariable UUID projectId) {
        return Blocking.call(() -> {
            dashboard.requireProject(projectId);
            return jdbc.query("""
                    SELECT e.id, b.id, b.agent_id, a.name, b.period, e.period_start, e.kind, e.spent_usd, b.limit_usd,
                           e.created_at
                    FROM alert_event e JOIN budget b ON b.id = e.budget_id LEFT JOIN agent a ON a.id = b.agent_id
                    WHERE b.project_id = ? ORDER BY e.created_at DESC, e.id DESC LIMIT ?
                    """,
                    (rs, i) -> new AlertView(rs.getLong(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                            rs.getString(4), rs.getString(5), rs.getObject(6, LocalDate.class), rs.getString(7),
                            Money.limit(rs.getBigDecimal(8)), Money.limit(rs.getBigDecimal(9)),
                            rs.getObject(10, OffsetDateTime.class).toInstant()),
                    projectId, LIMIT);
        });
    }
}
