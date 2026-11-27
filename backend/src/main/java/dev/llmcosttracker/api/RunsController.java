package dev.llmcosttracker.api;

import dev.llmcosttracker.api.DashboardQueries.AgentInfo;
import dev.llmcosttracker.api.DashboardQueries.RunEvent;
import dev.llmcosttracker.api.DashboardQueries.RunRow;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Runs, derived from events that carry {@code X-Tracker-Run-Id}. The list is newest first with cursor paging (50 per
 * page); the cursor is opaque to clients.
 */
@RestController
@RequestMapping(path = "/api/projects/{projectId}/runs", produces = MediaType.APPLICATION_JSON_VALUE)
public class RunsController {

    static final int PAGE_SIZE = 50;
    static final int MAX_RUN_EVENTS = 2_000;

    private final DashboardQueries queries;
    private final DashboardController dashboard;

    public RunsController(DashboardQueries queries, DashboardController dashboard) {
        this.queries = queries;
        this.dashboard = dashboard;
    }

    public record AgentName(UUID id, String name, String displayName) {}

    public record RunSummary(String runId, long calls, List<AgentName> agents, String costUsd, long inputTokens,
            long outputTokens, Instant startedAt, Instant endedAt, long durationMs, long errors, long incomplete) {}

    public record RunPage(Instant from, Instant to, List<RunSummary> runs, String nextCursor) {}

    @GetMapping
    public Mono<RunPage> list(@PathVariable UUID projectId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String cursor) {
        TimeRange r = dashboard.range(from, to);
        Cursor c = cursor == null || cursor.isBlank() ? null : Cursor.decode(cursor);
        return Blocking.call(() -> {
            dashboard.requireProject(projectId);
            List<RunRow> rows = queries.runs(projectId, r, c == null ? null : c.endedAt(),
                    c == null ? null : c.runId(), PAGE_SIZE + 1);
            Map<UUID, AgentName> names = agentNames(projectId);
            List<RunSummary> runs = rows.stream().limit(PAGE_SIZE).map(row -> new RunSummary(row.runId(),
                    row.calls(), row.agentIds().stream().map(id -> names.getOrDefault(id, new AgentName(id, null, null)))
                            .sorted(Comparator.comparing(a -> Objects.requireNonNullElse(a.name(), ""))).toList(),
                    Money.usd(row.costUsd()), row.inputTokens(), row.outputTokens(), row.startedAt(), row.endedAt(),
                    Duration.between(row.startedAt(), row.endedAt()).toMillis(), row.errors(), row.incomplete()))
                    .toList();
            String next = rows.size() > PAGE_SIZE
                    ? new Cursor(rows.get(PAGE_SIZE - 1).endedAt(), rows.get(PAGE_SIZE - 1).runId()).encode()
                    : null;
            return new RunPage(r.from(), r.to(), runs, next);
        });
    }

    public record CallView(int index, long id, UUID agentId, String agentName, String agentDisplayName,
            String provider, String endpoint, String model, long inputTokens, long outputTokens,
            long cacheReadTokens, long cacheWriteTokens, long reasoningTokens, String costUsd, boolean unpriced,
            boolean streamed, boolean incomplete, int statusCode, int latencyMs, Instant startedAt,
            long offsetMs) {}

    public record AgentCost(UUID id, String name, String displayName, long calls, String costUsd) {}

    public record RunDetail(String runId, Instant startedAt, Instant endedAt, long durationMs, long calls,
            String costUsd, long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens,
            long errors, long incomplete, List<String> models, List<AgentCost> agents, List<CallView> events,
            boolean truncated) {}

    @GetMapping("/{runId}")
    public Mono<RunDetail> detail(@PathVariable UUID projectId, @PathVariable String runId) {
        if (runId.length() > 128) {
            throw ApiException.validation("runId must be at most 128 characters");
        }
        return Blocking.call(() -> {
            dashboard.requireProject(projectId);
            List<RunEvent> events = queries.runEvents(projectId, runId, MAX_RUN_EVENTS + 1);
            if (events.isEmpty()) {
                throw ApiException.notFound("Unknown run");
            }
            boolean truncated = events.size() > MAX_RUN_EVENTS;
            if (truncated) {
                events = events.subList(0, MAX_RUN_EVENTS);
            }
            Map<UUID, AgentName> names = agentNames(projectId);
            Instant start = events.get(0).startedAt();
            Instant end = events.stream().map(RunEvent::endedAt).max(Comparator.naturalOrder()).orElse(start);

            Map<UUID, BigDecimal> agentCost = new LinkedHashMap<>();
            Map<UUID, Long> agentCalls = new HashMap<>();
            List<CallView> calls = new java.util.ArrayList<>();
            for (int i = 0; i < events.size(); i++) {
                RunEvent e = events.get(i);
                AgentName a = e.agentId() == null ? null : names.get(e.agentId());
                calls.add(new CallView(i + 1, e.id(), e.agentId(), a == null ? null : a.name(),
                        a == null ? null : a.displayName(), e.provider(), e.endpoint(), e.model(), e.inputTokens(),
                        e.outputTokens(), e.cacheReadTokens(), e.cacheWriteTokens(), e.reasoningTokens(),
                        Money.usd(e.costUsd()), e.costUsd() == null, e.streamed(), e.incomplete(), e.statusCode(),
                        e.latencyMs(), e.startedAt(), Duration.between(start, e.startedAt()).toMillis()));
                if (e.agentId() != null) {
                    agentCost.merge(e.agentId(), e.costUsd() == null ? BigDecimal.ZERO : e.costUsd(), BigDecimal::add);
                    agentCalls.merge(e.agentId(), 1L, Long::sum);
                }
            }
            List<AgentCost> agents = agentCost.entrySet().stream()
                    .sorted(Map.Entry.<UUID, BigDecimal>comparingByValue().reversed())
                    .map(en -> {
                        AgentName a = names.getOrDefault(en.getKey(), new AgentName(en.getKey(), null, null));
                        return new AgentCost(a.id(), a.name(), a.displayName(), agentCalls.get(en.getKey()),
                                Money.usd(en.getValue()));
                    }).toList();
            BigDecimal cost = events.stream().map(RunEvent::costUsd).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return new RunDetail(runId, start, end, Duration.between(start, end).toMillis(), events.size(),
                    Money.usd(cost),
                    events.stream().mapToLong(RunEvent::inputTokens).sum(),
                    events.stream().mapToLong(RunEvent::outputTokens).sum(),
                    events.stream().mapToLong(RunEvent::cacheReadTokens).sum(),
                    events.stream().mapToLong(RunEvent::cacheWriteTokens).sum(),
                    events.stream().filter(e -> e.statusCode() >= 400).count(),
                    events.stream().filter(RunEvent::incomplete).count(),
                    events.stream().map(RunEvent::model).filter(Objects::nonNull).distinct().toList(),
                    agents, calls, truncated);
        });
    }

    private Map<UUID, AgentName> agentNames(UUID projectId) {
        Map<UUID, AgentName> out = new HashMap<>();
        for (AgentInfo a : queries.agents(projectId)) {
            out.put(a.id(), new AgentName(a.id(), a.name(), a.displayName()));
        }
        return out;
    }

    /** Position after the last run of a page: its last-activity time and run id. */
    record Cursor(Instant endedAt, String runId) {

        String encode() {
            String raw = endedAt.getEpochSecond() + "." + endedAt.getNano() + ":" + runId;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String token) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
                int colon = raw.indexOf(':');
                String[] time = raw.substring(0, colon).split("\\.");
                String runId = raw.substring(colon + 1);
                if (time.length != 2 || runId.isEmpty() || runId.length() > 128) {
                    throw new IllegalArgumentException();
                }
                return new Cursor(Instant.ofEpochSecond(Long.parseLong(time[0]), Long.parseLong(time[1])), runId);
            } catch (RuntimeException e) {
                throw ApiException.validation("cursor is not valid");
            }
        }
    }
}
