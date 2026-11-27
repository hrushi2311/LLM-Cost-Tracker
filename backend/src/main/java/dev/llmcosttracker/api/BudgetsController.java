package dev.llmcosttracker.api;

import dev.llmcosttracker.budget.Budget;
import dev.llmcosttracker.budget.BudgetRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Budget CRUD. Alerts, the in-memory spend cache and enforcement arrive in milestone 7; the {@code enforce} flag is
 * stored now and has no effect until then.
 */
@RestController
@RequestMapping(path = "/api/projects/{projectId}/budgets", produces = MediaType.APPLICATION_JSON_VALUE)
public class BudgetsController {

    static final BigDecimal MAX_LIMIT = new BigDecimal("9999999999.99"); // NUMERIC(12,2)

    /** {@code agentId} null = the whole project. {@code alertPct} defaults to 80, {@code enforce} to false. */
    public record BudgetRequest(String agentId, String period, BigDecimal limitUsd, Integer alertPct,
            Boolean enforce) {}

    private final BudgetRepository budgets;
    private final DashboardController dashboard;
    private final JdbcTemplate jdbc;

    public BudgetsController(BudgetRepository budgets, DashboardController dashboard, JdbcTemplate jdbc) {
        this.budgets = budgets;
        this.dashboard = dashboard;
        this.jdbc = jdbc;
    }

    @GetMapping
    public Mono<List<BudgetView>> list(@PathVariable UUID projectId) {
        return Blocking.call(() -> {
            dashboard.requireProject(projectId);
            return dashboard.budgetViews(projectId);
        });
    }

    @GetMapping("/{budgetId}")
    public Mono<BudgetView> get(@PathVariable UUID projectId, @PathVariable UUID budgetId) {
        return Blocking.call(() -> view(projectId, budgetId));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<BudgetView> create(@PathVariable UUID projectId, @RequestBody BudgetRequest body) {
        return Blocking.call(() -> {
            dashboard.requireProject(projectId);
            Budget b = validate(projectId, UUID.randomUUID(), body);
            try {
                budgets.insert(b);
            } catch (DuplicateKeyException e) {
                throw duplicate();
            }
            return view(projectId, b.id());
        });
    }

    @PutMapping(path = "/{budgetId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<BudgetView> update(@PathVariable UUID projectId, @PathVariable UUID budgetId,
            @RequestBody BudgetRequest body) {
        return Blocking.call(() -> {
            dashboard.requireProject(projectId);
            budgets.find(projectId, budgetId).orElseThrow(() -> ApiException.notFound("Unknown budget"));
            Budget b = validate(projectId, budgetId, body);
            try {
                if (!budgets.update(b)) {
                    throw ApiException.notFound("Unknown budget");
                }
            } catch (DuplicateKeyException e) {
                throw duplicate();
            }
            return view(projectId, budgetId);
        });
    }

    @DeleteMapping("/{budgetId}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable UUID projectId, @PathVariable UUID budgetId) {
        return Blocking.call(() -> {
            dashboard.requireProject(projectId);
            if (!budgets.delete(projectId, budgetId)) {
                throw ApiException.notFound("Unknown budget");
            }
            return ResponseEntity.noContent().<Void>build();
        });
    }

    private BudgetView view(UUID projectId, UUID budgetId) {
        dashboard.requireProject(projectId);
        return dashboard.budgetViews(projectId).stream().filter(v -> v.id().equals(budgetId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Unknown budget"));
    }

    private Budget validate(UUID projectId, UUID id, BudgetRequest body) {
        UUID agentId = null;
        if (body.agentId() != null) {
            agentId = Validation.uuid("agentId", body.agentId());
            Boolean inProject = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM agent WHERE id = ? AND project_id = ?)", Boolean.class,
                    agentId, projectId);
            if (!Boolean.TRUE.equals(inProject)) {
                throw ApiException.validation("agentId is not an agent of this project");
            }
        }
        if (body.period() == null || !(body.period().equals("day") || body.period().equals("month"))) {
            throw ApiException.validation("period must be 'day' or 'month'");
        }
        BigDecimal limit = Validation.decimal("limitUsd", body.limitUsd(), 2, BigDecimal.ZERO, true, MAX_LIMIT);
        int alertPct = body.alertPct() == null ? 80 : body.alertPct();
        if (alertPct < 1 || alertPct > 100) {
            throw ApiException.validation("alertPct must be between 1 and 100");
        }
        return new Budget(id, projectId, agentId, body.period(), limit, alertPct,
                Boolean.TRUE.equals(body.enforce()));
    }

    private static ApiException duplicate() {
        return ApiException.conflict("There is already a budget for this agent (or project) and period");
    }
}
