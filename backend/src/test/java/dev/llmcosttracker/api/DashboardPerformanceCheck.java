package dev.llmcosttracker.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.llmcosttracker.demo.DemoSeeder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Manual performance check, not part of {@code test} or CI: {@code ./gradlew :backend:perfTest}.
 *
 * <p>Seeds one million demo events over 30 days into a stock Postgres 16 container, then times the dashboard's
 * heaviest reads end to end over HTTP (query, JSON, transport): each endpoint once to warm up, then 5 timed calls.
 * The bar is 500 ms per call; the median and the slowest timed call are printed and the slowest is asserted.
 */
@Tag("perf")
@org.springframework.test.context.TestPropertySource(properties = {
    // Timings, not log checks: keep request logging at its normal level.
    "logging.level.org.springframework.web=INFO",
    "logging.level.reactor.netty.http=INFO"
})
class DashboardPerformanceCheck extends ApiTestBase {

    static final int EVENTS = Integer.getInteger("perf.events", 1_000_000);
    static final int RUNS = 5;
    static final long LIMIT_MS = 500;

    @Autowired
    DemoSeeder seeder;

    @Autowired
    DashboardQueries queries;

    @Test
    void dashboardQueriesStayUnder500msWithOneMillionEvents() {
        long seedStart = System.nanoTime();
        DemoSeeder.Result seeded = seeder.seed("perf", EVENTS, 30, 7L);
        System.out.printf("%nSeeded %,d events (%,d runs) in %.1f s%n", seeded.events(), seeded.runs(),
                (System.nanoTime() - seedStart) / 1e9);
        signIn(); // the seed took a while; sign in again so the session is fresh

        Instant now = clock.instant();
        String last30 = "from=" + now.minus(Duration.ofDays(30)) + "&to=" + now;
        String id = seeded.projectId().toString();
        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("summary (default 14 days)", "/api/projects/" + id + "/summary");
        endpoints.put("summary (30 days)", "/api/projects/" + id + "/summary?" + last30);
        endpoints.put("timeseries agent/day (14 days)", "/api/projects/" + id + "/timeseries?groupBy=agent&interval=day");
        endpoints.put("timeseries agent/day (30 days)",
                "/api/projects/" + id + "/timeseries?groupBy=agent&interval=day&" + last30);
        endpoints.put("timeseries model/hour (14 days)",
                "/api/projects/" + id + "/timeseries?groupBy=model&interval=hour");
        endpoints.put("agents (14 days)", "/api/projects/" + id + "/agents");
        endpoints.put("agents (30 days)", "/api/projects/" + id + "/agents?" + last30);
        endpoints.put("models (14 days)", "/api/projects/" + id + "/models");
        endpoints.put("models (30 days)", "/api/projects/" + id + "/models?" + last30);
        endpoints.put("runs, first page (14 days)", "/api/projects/" + id + "/runs");

        List<String> report = new ArrayList<>();
        Map<String, Long> slowest = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : endpoints.entrySet()) {
            call(e.getValue()); // warm-up
            long[] ms = new long[RUNS];
            for (int i = 0; i < RUNS; i++) {
                long t0 = System.nanoTime();
                call(e.getValue());
                ms[i] = (System.nanoTime() - t0) / 1_000_000;
            }
            Arrays.sort(ms);
            slowest.put(e.getKey(), ms[RUNS - 1]);
            report.add(String.format("  %-34s median %4d ms   slowest %4d ms   %s", e.getKey(), ms[RUNS / 2],
                    ms[RUNS - 1], Arrays.toString(ms)));
        }
        System.out.println("Dashboard API timings, " + String.format("%,d", seeded.events()) + " events:");
        report.forEach(System.out::println);
        explain(seeded.projectId());

        System.out.flush();
        assertThat(slowest).allSatisfy((name, ms) -> assertThat(ms).as(name).isLessThan(LIMIT_MS));
    }

    /** Prints the plans of the summary's two queries (default range, with budgets), for diagnosis. */
    private void explain(java.util.UUID projectId) {
        Instant now = clock.instant();
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Kolkata");
        TimeRange r = TimeRange.parse(null, null, now, zone);
        DashboardQueries.Query q = queries.slicesQuery(projectId, new DashboardQueries.Windows(r, r.previous(),
                dev.llmcosttracker.budget.BudgetPeriod.containing("month", now, zone).start(),
                dev.llmcosttracker.budget.BudgetPeriod.containing("day", now, zone).start()), false);
        System.out.println("EXPLAIN summary slices:");
        jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + q.sql(), String.class, q.args()).forEach(System.out::println);
        System.out.println("EXPLAIN run count:");
        jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM (SELECT run_id FROM usage_event "
                        + "WHERE project_id = ? AND run_id IS NOT NULL AND created_at >= ? AND created_at < ? "
                        + "GROUP BY run_id) runs", String.class, projectId, r.from().atOffset(java.time.ZoneOffset.UTC),
                r.to().atOffset(java.time.ZoneOffset.UTC)).forEach(System.out::println);
    }

    private void call(String uri) {
        get(uri).exchange().expectStatus().isOk().expectBody().returnResult();
    }
}
