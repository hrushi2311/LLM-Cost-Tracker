package dev.llmcosttracker.api;

import dev.llmcosttracker.api.DashboardQueries.AgentInfo;
import dev.llmcosttracker.api.DashboardQueries.Cell;
import dev.llmcosttracker.api.DashboardQueries.ModelRow;
import dev.llmcosttracker.api.DashboardQueries.Slice;
import dev.llmcosttracker.api.DashboardQueries.Stats;
import dev.llmcosttracker.api.DashboardQueries.Windows;
import dev.llmcosttracker.budget.BudgetPeriod;
import dev.llmcosttracker.auth.ProjectRepository;
import dev.llmcosttracker.budget.Budget;
import dev.llmcosttracker.budget.BudgetRepository;
import dev.llmcosttracker.budget.BudgetStatus;
import dev.llmcosttracker.config.TrackerProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Overview and Agents screens: summary, timeseries, cost by model, agents. All take {@code from}/{@code to}. */
@RestController
@RequestMapping(path = "/api/projects/{projectId}", produces = MediaType.APPLICATION_JSON_VALUE)
public class DashboardController {

    static final Duration MAX_HOURLY_SPAN = Duration.ofDays(31);

    private final DashboardQueries queries;
    private final ProjectRepository projects;
    private final BudgetRepository budgets;
    private final Clock clock;
    private final ZoneId zone;

    public DashboardController(DashboardQueries queries, ProjectRepository projects, BudgetRepository budgets,
            Clock clock, TrackerProperties props) {
        this.queries = queries;
        this.projects = projects;
        this.budgets = budgets;
        this.clock = clock;
        this.zone = props.timezone();
    }

    void requireProject(UUID projectId) {
        if (!projects.exists(projectId)) {
            throw ApiException.notFound("Unknown project");
        }
    }

    TimeRange range(String from, String to) {
        return TimeRange.parse(from, to, clock.instant(), zone);
    }

    /** Budgets with current-period spend; spend is only queried when the project has budgets. */
    List<BudgetView> budgetViews(UUID projectId) {
        List<Budget> list = budgets.list(projectId);
        if (list.isEmpty()) {
            return List.of();
        }
        Instant now = clock.instant();
        return views(list, budgets.currentSpend(projectId, now, zone), now);
    }

    private List<BudgetView> views(List<Budget> list, Map<UUID, BudgetRepository.Spend> spend, Instant now) {
        return list.stream().map(b -> {
            BigDecimal spent = spend.getOrDefault(b.agentId(), BudgetRepository.NONE).forPeriod(b.period());
            return BudgetView.of(b, BudgetStatus.of(b, spent, now, zone), zone);
        }).toList();
    }

    /** Windows for one scan: the range, optionally the previous period, and the budget day and month if needed. */
    private Windows windows(TimeRange r, boolean previous, boolean budgetsToo, Instant now) {
        return new Windows(r, previous ? r.previous() : null,
                budgetsToo ? BudgetPeriod.containing("month", now, zone).start() : null,
                budgetsToo ? BudgetPeriod.containing("day", now, zone).start() : null);
    }

    /** Current day and month spend per agent (null key = whole project), from slices tagged with those windows. */
    private static Map<UUID, BudgetRepository.Spend> spendFrom(List<Slice> slices) {
        Map<UUID, BigDecimal[]> sums = new HashMap<>();
        BigDecimal[] total = {BigDecimal.ZERO, BigDecimal.ZERO};
        for (Slice s : slices) {
            BigDecimal[] a = s.agentId() == null ? null
                    : sums.computeIfAbsent(s.agentId(), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            if (s.inDay()) {
                total[0] = total[0].add(s.stats().costUsd());
                if (a != null) {
                    a[0] = a[0].add(s.stats().costUsd());
                }
            }
            if (s.inMonth()) {
                total[1] = total[1].add(s.stats().costUsd());
                if (a != null) {
                    a[1] = a[1].add(s.stats().costUsd());
                }
            }
        }
        Map<UUID, BudgetRepository.Spend> out = new HashMap<>();
        sums.forEach((k, v) -> out.put(k, new BudgetRepository.Spend(v[0], v[1])));
        out.put(null, new BudgetRepository.Spend(total[0], total[1]));
        return out;
    }

    // --- summary -------------------------------------------------------------------------------------------------

    public record Tokens(long input, long output, long cacheRead, long cacheWrite, long reasoning, long total) {}

    public record Summary(
            UUID projectId, Instant from, Instant to, String timezone,
            String costUsd, String previousCostUsd, String costChange,
            long requests, long previousRequests,
            Tokens tokens,
            long runs, String avgCostPerRunUsd,
            long errors, String errorRate,
            long incomplete, String incompleteRate,
            long unpricedRequests, long activeAgents,
            List<BudgetView> budgets) {}

    @GetMapping("/summary")
    public Mono<Summary> summary(@PathVariable UUID projectId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        TimeRange r = range(from, to);
        return Blocking.call(() -> {
            requireProject(projectId);
            Instant now = clock.instant();
            List<Budget> projectBudgets = budgets.list(projectId).stream().filter(b -> b.agentId() == null).toList();
            // One pass: range, previous period and (if there are budgets) the budget day and month.
            List<Slice> slices = queries.slices(projectId, windows(r, true, !projectBudgets.isEmpty(), now), false);
            Stats t = Stats.ZERO;
            Stats prev = Stats.ZERO;
            java.util.Set<UUID> agentsInRange = new java.util.HashSet<>();
            for (Slice s : slices) {
                if (s.inRange()) {
                    t = t.plus(s.stats());
                    if (s.agentId() != null) {
                        agentsInRange.add(s.agentId());
                    }
                }
                if (s.inPrevious()) {
                    prev = prev.plus(s.stats());
                }
            }
            long runs = t.requests() == 0 ? 0 : queries.runCount(projectId, r);
            BigDecimal change = prev.costUsd().signum() == 0 ? null
                    : t.costUsd().subtract(prev.costUsd()).divide(prev.costUsd(), 4, RoundingMode.HALF_UP);
            String avgPerRun = runs == 0 ? null
                    : Money.usd(t.runCostUsd().divide(BigDecimal.valueOf(runs), 8, RoundingMode.HALF_UP));
            return new Summary(projectId, r.from(), r.to(), zone.getId(),
                    Money.usd(t.costUsd()), Money.usd(prev.costUsd()), change == null ? null : change.toPlainString(),
                    t.requests(), prev.requests(),
                    new Tokens(t.inputTokens(), t.outputTokens(), t.cacheReadTokens(), t.cacheWriteTokens(),
                            t.reasoningTokens(),
                            t.inputTokens() + t.outputTokens() + t.cacheReadTokens() + t.cacheWriteTokens()),
                    runs, avgPerRun,
                    t.errors(), Money.ratio(t.errors(), t.requests()),
                    t.incomplete(), Money.ratio(t.incomplete(), t.requests()),
                    t.unpriced(), agentsInRange.size(),
                    views(projectBudgets, spendFrom(slices), now));
        });
    }

    // --- timeseries ----------------------------------------------------------------------------------------------

    public record Series(String key, String label, String provider, String source, String totalCostUsd,
            List<String> costUsd, List<Long> requests) {}

    public record Timeseries(Instant from, Instant to, String timezone, String groupBy, String interval,
            List<Instant> buckets, List<Series> series) {}

    @GetMapping("/timeseries")
    public Mono<Timeseries> timeseries(@PathVariable UUID projectId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "agent") String groupBy, @RequestParam(defaultValue = "day") String interval) {
        if (!groupBy.equals("agent") && !groupBy.equals("model")) {
            throw ApiException.validation("groupBy must be 'agent' or 'model'");
        }
        if (!interval.equals("hour") && !interval.equals("day")) {
            throw ApiException.validation("interval must be 'hour' or 'day'");
        }
        TimeRange r = range(from, to);
        if (interval.equals("hour") && r.length().compareTo(MAX_HOURLY_SPAN) > 0) {
            throw ApiException.validation("interval=hour allows a range of at most 31 days");
        }
        return Blocking.call(() -> {
            requireProject(projectId);
            List<Cell> cells = queries.timeseries(projectId, r, groupBy);
            // Each 15-minute UTC slot lies inside one local hour/day; map slots to bucket starts once each.
            Map<Instant, Instant> slotToBucket = new HashMap<>();
            for (Cell c : cells) {
                slotToBucket.computeIfAbsent(c.slot(), slot -> bucketStart(slot, interval, zone));
            }
            TreeSet<Instant> bucketSet = new TreeSet<>(bucketStarts(r, interval, zone));
            bucketSet.addAll(slotToBucket.values());
            List<Instant> buckets = List.copyOf(bucketSet);
            Map<Instant, Integer> index = new HashMap<>();
            for (int i = 0; i < buckets.size(); i++) {
                index.put(buckets.get(i), i);
            }

            Map<String, BigDecimal[]> costs = new LinkedHashMap<>();
            Map<String, long[]> counts = new HashMap<>();
            for (Cell c : cells) {
                String key = c.group() == null ? "none" : c.group();
                BigDecimal[] row = costs.computeIfAbsent(key, k -> zeros(buckets.size()));
                long[] n = counts.computeIfAbsent(key, k -> new long[buckets.size()]);
                int i = index.get(slotToBucket.get(c.slot()));
                row[i] = row[i].add(c.costUsd());
                n[i] += c.requests();
            }

            Map<String, AgentInfo> agents = new HashMap<>();
            if (groupBy.equals("agent")) {
                queries.agents(projectId).forEach(a -> agents.put(a.id().toString(), a));
            }
            List<Series> series = new ArrayList<>();
            costs.forEach((key, row) -> {
                BigDecimal total = java.util.Arrays.stream(row).reduce(BigDecimal.ZERO, BigDecimal::add);
                String label;
                String provider = null;
                String source = null;
                if (groupBy.equals("agent")) {
                    AgentInfo a = agents.get(key);
                    label = a == null ? "(deleted agent)" : Objects.requireNonNullElse(a.displayName(), a.name());
                    source = a == null ? null : a.source();
                } else {
                    int slash = key.indexOf('/');
                    provider = key.substring(0, slash);
                    label = key.substring(slash + 1).isEmpty() ? "(unknown model)" : key.substring(slash + 1);
                }
                series.add(new Series(key, label, provider, source, Money.usd(total),
                        java.util.Arrays.stream(row).map(Money::usd).toList(),
                        java.util.Arrays.stream(counts.get(key)).boxed().toList()));
            });
            series.sort(Comparator.comparing((Series s) -> new BigDecimal(s.totalCostUsd())).reversed()
                    .thenComparing(Series::label));
            return new Timeseries(r.from(), r.to(), zone.getId(), groupBy, interval, buckets, series);
        });
    }

    static Instant bucketStart(Instant t, String interval, ZoneId zone) {
        ZonedDateTime local = t.atZone(zone);
        return interval.equals("day")
                ? local.toLocalDate().atStartOfDay(zone).toInstant()
                : local.truncatedTo(ChronoUnit.HOURS).toInstant();
    }

    /** Every bucket start overlapping the range, so empty hours/days still appear as zeros. */
    static List<Instant> bucketStarts(TimeRange r, String interval, ZoneId zone) {
        List<Instant> out = new ArrayList<>();
        ZonedDateTime start = r.from().atZone(zone);
        if (interval.equals("day")) {
            LocalDate d = start.toLocalDate();
            for (Instant t = d.atStartOfDay(zone).toInstant(); t.isBefore(r.to());
                    d = d.plusDays(1), t = d.atStartOfDay(zone).toInstant()) {
                out.add(t);
            }
        } else {
            // Hours are truncated in the zone (Asia/Kolkata hours start at :30 UTC) and stepped in absolute time.
            Instant t = start.truncatedTo(ChronoUnit.HOURS).toInstant();
            for (; t.isBefore(r.to()); t = t.plus(Duration.ofHours(1))) {
                out.add(t);
            }
        }
        return out;
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        java.util.Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    // --- models --------------------------------------------------------------------------------------------------

    public record ModelView(String provider, String model, long requests, long inputTokens, long outputTokens,
            long cacheReadTokens, long cacheWriteTokens, long reasoningTokens, String costUsd, String share,
            boolean unpriced, long unpricedRequests) {}

    public record Models(Instant from, Instant to, String totalCostUsd, List<ModelView> models) {}

    @GetMapping("/models")
    public Mono<Models> models(@PathVariable UUID projectId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        TimeRange r = range(from, to);
        return Blocking.call(() -> {
            requireProject(projectId);
            List<ModelRow> rows = queries.models(projectId, r);
            BigDecimal total = rows.stream().map(ModelRow::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new Models(r.from(), r.to(), Money.usd(total), rows.stream().map(m -> new ModelView(
                    m.provider(), m.model(), m.requests(), m.inputTokens(), m.outputTokens(), m.cacheReadTokens(),
                    m.cacheWriteTokens(), m.reasoningTokens(), Money.usd(m.costUsd()), Money.ratio(m.costUsd(), total),
                    m.unpricedRequests() > 0, m.unpricedRequests())).toList());
        });
    }

    // --- agents --------------------------------------------------------------------------------------------------

    public record AgentModel(String provider, String model, long requests, String costUsd, String share) {}

    /** Range totals for one agent and model. */
    record AgentModelRow(String provider, String model, long requests, long inputTokens, long outputTokens,
            long cacheReadTokens, long cacheWriteTokens, BigDecimal costUsd, long errors, long unpricedRequests) {

        static AgentModelRow of(Slice s) {
            Stats t = s.stats();
            return new AgentModelRow(s.provider(), s.model(), t.requests(), t.inputTokens(), t.outputTokens(),
                    t.cacheReadTokens(), t.cacheWriteTokens(), t.costUsd(), t.errors(), t.unpriced());
        }

        AgentModelRow plus(AgentModelRow o) {
            return new AgentModelRow(provider, model, requests + o.requests, inputTokens + o.inputTokens,
                    outputTokens + o.outputTokens, cacheReadTokens + o.cacheReadTokens,
                    cacheWriteTokens + o.cacheWriteTokens, costUsd.add(o.costUsd), errors + o.errors,
                    unpricedRequests + o.unpricedRequests);
        }
    }

    public record AgentView(UUID id, String name, String displayName, String source, Instant firstSeen,
            Instant lastSeen, long requests, long inputTokens, long outputTokens, long cacheReadTokens,
            long cacheWriteTokens, String costUsd, long errors, long unpricedRequests, List<AgentModel> models,
            List<BudgetView> budgets) {}

    public record Agents(Instant from, Instant to, List<AgentView> agents) {}

    @GetMapping("/agents")
    public Mono<Agents> agents(@PathVariable UUID projectId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        TimeRange r = range(from, to);
        return Blocking.call(() -> {
            requireProject(projectId);
            Instant now = clock.instant();
            List<Budget> agentBudgets = budgets.list(projectId).stream().filter(b -> b.agentId() != null).toList();
            // One pass: the range per agent and model, plus the budget day and month if any agent has a budget.
            List<Slice> slices = queries.slices(projectId, windows(r, false, !agentBudgets.isEmpty(), now), true);
            Map<UUID, Map<String, AgentModelRow>> grouped = new HashMap<>();
            for (Slice s : slices) {
                if (s.agentId() == null || !s.inRange()) {
                    continue;
                }
                grouped.computeIfAbsent(s.agentId(), k -> new LinkedHashMap<>())
                        .merge(s.provider() + "/" + s.model(), AgentModelRow.of(s), AgentModelRow::plus);
            }
            Map<UUID, List<AgentModelRow>> byAgent = new HashMap<>();
            grouped.forEach((agent, rows) -> byAgent.put(agent, List.copyOf(rows.values())));
            Map<UUID, List<BudgetView>> budgetsByAgent = new HashMap<>();
            views(agentBudgets, spendFrom(slices), now)
                    .forEach(b -> budgetsByAgent.computeIfAbsent(b.agentId(), k -> new ArrayList<>()).add(b));

            List<AgentView> views = new ArrayList<>();
            for (AgentInfo a : queries.agents(projectId)) {
                List<AgentModelRow> rows = byAgent.getOrDefault(a.id(), List.of());
                BigDecimal cost = rows.stream().map(AgentModelRow::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
                List<AgentModel> models = rows.stream()
                        .sorted(Comparator.comparing(AgentModelRow::costUsd).reversed()
                                .thenComparing(Comparator.comparingLong(AgentModelRow::requests).reversed()))
                        .map(m -> new AgentModel(m.provider(), m.model(), m.requests(), Money.usd(m.costUsd()),
                                Money.ratio(m.costUsd(), cost)))
                        .toList();
                views.add(new AgentView(a.id(), a.name(), a.displayName(), a.source(), a.firstSeen(), a.lastSeen(),
                        rows.stream().mapToLong(AgentModelRow::requests).sum(),
                        rows.stream().mapToLong(AgentModelRow::inputTokens).sum(),
                        rows.stream().mapToLong(AgentModelRow::outputTokens).sum(),
                        rows.stream().mapToLong(AgentModelRow::cacheReadTokens).sum(),
                        rows.stream().mapToLong(AgentModelRow::cacheWriteTokens).sum(),
                        Money.usd(cost),
                        rows.stream().mapToLong(AgentModelRow::errors).sum(),
                        rows.stream().mapToLong(AgentModelRow::unpricedRequests).sum(),
                        models, budgetsByAgent.getOrDefault(a.id(), List.of())));
            }
            views.sort(Comparator.comparing((AgentView v) -> new BigDecimal(v.costUsd())).reversed()
                    .thenComparing(Comparator.comparingLong(AgentView::requests).reversed())
                    .thenComparing(AgentView::name));
            return new Agents(r.from(), r.to(), views);
        });
    }
}
