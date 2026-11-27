package dev.llmcosttracker.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Summary, timeseries, models, agents and runs against a small hand-made dataset. TRACKER_TIMEZONE is Asia/Kolkata
 * (UTC+05:30), so day buckets start at 18:30 UTC. "Now" is 2026-11-20T06:30Z (12:00 in Kolkata).
 *
 * <pre>
 * #  agent  run  provider/model                cost   status  incomplete  created_at (UTC)     latency
 * 1  A      r1   openai/gpt-4o  1000/200       0.50   200                 2026-11-18T10:00:00  1000
 * 2  B      r1   anthropic/claude  2000/300/cr500  1.25   200          2026-11-18T10:00:03  2000
 * 3  A      -    openai/gpt-4o  500/100        0.25   200                 2026-11-19T05:00:00  500
 * 4  F      -    openai/gpt-4o  0/0            0      429                 2026-11-19T06:00:00  100
 * 5  B      r2   anthropic/claude  100/10      0.10   200     yes         2026-11-19T20:00:00  300
 * 6  A      r2   openai/mystery-model 300/30   null   200                 2026-11-19T20:00:05  400
 * 7  A      -    openai/gpt-4o (previous period) 1.00                     2026-11-17T12:00:00
 * 8  A      -    openai/gpt-4o (after the range) 0.99                     2026-11-20T01:00:00
 * </pre>
 * Range: [2026-11-18T00:00Z, 2026-11-20T00:00Z).
 */
class DashboardApiTest extends ApiTestBase {

    static final Instant NOW = Instant.parse("2026-11-20T06:30:00Z");
    static final String RANGE = "from=2026-11-18T00:00:00Z&to=2026-11-20T00:00:00Z";

    UUID p;
    UUID a;
    UUID b;
    UUID f;

    @Override
    protected Instant testNow() {
        return NOW;
    }

    @BeforeEach
    void data() {
        p = project("support-bot");
        a = agent(p, "summary-agent", "path");
        b = agent(p, "refund-agent", "header");
        f = agent(p, "agent-ffff", "fingerprint");
        event(p, a, "r1", "openai", "gpt-4o", 1000, 200, 0, "0.50", 200, false, 1000, t("2026-11-18T10:00:00Z"));
        event(p, b, "r1", "anthropic", "claude-sonnet-5-5", 2000, 300, 500, "1.25", 200, false, 2000,
                t("2026-11-18T10:00:03Z"));
        event(p, a, null, "openai", "gpt-4o", 500, 100, 0, "0.25", 200, false, 500, t("2026-11-19T05:00:00Z"));
        event(p, f, null, "openai", "gpt-4o", 0, 0, 0, "0", 429, false, 100, t("2026-11-19T06:00:00Z"));
        event(p, b, "r2", "anthropic", "claude-sonnet-5-5", 100, 10, 0, "0.10", 200, true, 300,
                t("2026-11-19T20:00:00Z"));
        event(p, a, "r2", "openai", "mystery-model", 300, 30, 0, null, 200, false, 400, t("2026-11-19T20:00:05Z"));
        event(p, a, null, "openai", "gpt-4o", 10, 10, 0, "1.00", 200, false, 100, t("2026-11-17T12:00:00Z"));
        event(p, a, null, "openai", "gpt-4o", 10, 10, 0, "0.99", 200, false, 100, t("2026-11-20T01:00:00Z"));
        jdbc.update("INSERT INTO budget (id, project_id, agent_id, period, limit_usd, alert_pct) "
                + "VALUES (?, ?, NULL, 'month', 10.00, 80)", UUID.randomUUID(), p);
        jdbc.update("INSERT INTO budget (id, project_id, agent_id, period, limit_usd, alert_pct) "
                + "VALUES (?, ?, ?, 'month', 2.00, 80)", UUID.randomUUID(), p, b);
    }

    static Instant t(String s) {
        return Instant.parse(s);
    }

    @Test
    void summary() {
        get("/api/projects/{id}/summary?" + RANGE, p).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.timezone").isEqualTo("Asia/Kolkata")
                .jsonPath("$.costUsd").isEqualTo("2.10000000")              // 0.50+1.25+0.25+0+0.10 (+ unpriced)
                .jsonPath("$.previousCostUsd").isEqualTo("1.00000000")
                .jsonPath("$.costChange").isEqualTo("1.1000")               // +110 %
                .jsonPath("$.requests").isEqualTo(6)
                .jsonPath("$.previousRequests").isEqualTo(1)
                .jsonPath("$.tokens.input").isEqualTo(3900)
                .jsonPath("$.tokens.output").isEqualTo(640)
                .jsonPath("$.tokens.cacheRead").isEqualTo(500)
                .jsonPath("$.tokens.total").isEqualTo(5040)
                .jsonPath("$.runs").isEqualTo(2)
                .jsonPath("$.avgCostPerRunUsd").isEqualTo("0.92500000")     // (0.50+1.25+0.10) / 2
                .jsonPath("$.errors").isEqualTo(1)
                .jsonPath("$.errorRate").isEqualTo("0.1667")
                .jsonPath("$.incomplete").isEqualTo(1)                      // its own count, beside the error rate
                .jsonPath("$.incompleteRate").isEqualTo("0.1667")
                .jsonPath("$.unpricedRequests").isEqualTo(1)
                .jsonPath("$.activeAgents").isEqualTo(3)
                // Project budget only. Month-to-date in Kolkata: Nov 1 00:00 IST = Oct 31 18:30Z, 19.5 days ago;
                // spend 2.10 + 1.00 + 0.99 = 4.09, projected 4.09 × 30 / 19.5.
                .jsonPath("$.budgets").value(hasSize(1))
                .jsonPath("$.budgets[0].agentId").value(nullValue())
                .jsonPath("$.budgets[0].periodStart").isEqualTo("2026-11-01")
                .jsonPath("$.budgets[0].periodEnd").isEqualTo("2026-11-30")
                .jsonPath("$.budgets[0].limitUsd").isEqualTo("10.00")
                .jsonPath("$.budgets[0].spentUsd").isEqualTo("4.09000000")
                .jsonPath("$.budgets[0].usedFraction").isEqualTo("0.4090")
                .jsonPath("$.budgets[0].projectedUsd").isEqualTo("6.29230769")
                .jsonPath("$.budgets[0].thresholdReached").isEqualTo(false)
                .jsonPath("$.budgets[0].projectedAlertDate").value(nullValue()); // 8.00 is not reached this month
    }

    @Test
    void summaryDefaultsToTheLastFourteenDays() {
        get("/api/projects/{id}/summary", p).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.to").isEqualTo("2026-11-20T06:30:00Z")
                .jsonPath("$.from").isEqualTo("2026-11-06T06:30:00Z")
                .jsonPath("$.requests").isEqualTo(8);
    }

    @Test
    void summaryOfAnEmptyProjectHasZerosNotNulls() {
        UUID empty = project("empty");
        get("/api/projects/{id}/summary", empty).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.costUsd").isEqualTo("0.00000000")
                .jsonPath("$.requests").isEqualTo(0)
                .jsonPath("$.errorRate").value(nullValue())
                .jsonPath("$.costChange").value(nullValue())
                .jsonPath("$.avgCostPerRunUsd").value(nullValue())
                .jsonPath("$.budgets").value(hasSize(0));
    }

    @Test
    void timeseriesByAgentPerKolkataDay() {
        get("/api/projects/{id}/timeseries?groupBy=agent&interval=day&" + RANGE, p).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.buckets").value(contains("2026-11-17T18:30:00Z", "2026-11-18T18:30:00Z", "2026-11-19T18:30:00Z"))
                .jsonPath("$.series[0].key").isEqualTo(b.toString())
                .jsonPath("$.series[0].label").isEqualTo("refund-agent")
                .jsonPath("$.series[0].source").isEqualTo("header")
                .jsonPath("$.series[0].totalCostUsd").isEqualTo("1.35000000")
                .jsonPath("$.series[0].costUsd").value(contains("1.25000000", "0.00000000", "0.10000000"))
                .jsonPath("$.series[1].label").isEqualTo("summary-agent")
                .jsonPath("$.series[1].costUsd").value(contains("0.50000000", "0.25000000", "0.00000000"))
                .jsonPath("$.series[1].requests").value(contains(1, 1, 1))
                .jsonPath("$.series[2].label").isEqualTo("agent-ffff")
                .jsonPath("$.series[2].source").isEqualTo("fingerprint")
                .jsonPath("$.series[2].requests").value(contains(0, 1, 0));
    }

    @Test
    void timeseriesByModelPerHourFillsEmptyHours() {
        get("/api/projects/{id}/timeseries?groupBy=model&interval=hour&" + RANGE, p).exchange()
                .expectStatus().isOk()
                .expectBody()
                // Kolkata hours start at :30 UTC: 23:30 the day before the range up to 23:30 on its last day.
                .jsonPath("$.buckets").value(hasSize(49))
                .jsonPath("$.buckets[0]").isEqualTo("2026-11-17T23:30:00Z")
                .jsonPath("$.series[0].key").isEqualTo("anthropic/claude-sonnet-5-5")
                .jsonPath("$.series[0].provider").isEqualTo("anthropic")
                .jsonPath("$.series[0].label").isEqualTo("claude-sonnet-5-5")
                .jsonPath("$.series[0].totalCostUsd").isEqualTo("1.35000000")
                .jsonPath("$.series[1].key").isEqualTo("openai/gpt-4o")
                .jsonPath("$.series[1].totalCostUsd").isEqualTo("0.75000000")
                .jsonPath("$.series[2].key").isEqualTo("openai/mystery-model");
    }

    @Test
    void timeseriesValidatesItsParameters() {
        for (String q : List.of("groupBy=team", "interval=minute", "interval=hour&from=2026-09-01&to=2026-11-01",
                "from=2026-11-20T00:00:00Z&to=2026-11-18T00:00:00Z", "from=yesterday", "from=2024-01-01&to=2026-01-01")) {
            get("/api/projects/{id}/timeseries?" + q, p).exchange()
                    .expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.error.code").isEqualTo("validation_error");
        }
    }

    @Test
    void models() {
        get("/api/projects/{id}/models?" + RANGE, p).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalCostUsd").isEqualTo("2.10000000")
                .jsonPath("$.models").value(hasSize(3))
                .jsonPath("$.models[0].model").isEqualTo("claude-sonnet-5-5")
                .jsonPath("$.models[0].provider").isEqualTo("anthropic")
                .jsonPath("$.models[0].requests").isEqualTo(2)
                .jsonPath("$.models[0].costUsd").isEqualTo("1.35000000")
                .jsonPath("$.models[0].share").isEqualTo("0.6429")
                .jsonPath("$.models[0].unpriced").isEqualTo(false)
                .jsonPath("$.models[1].model").isEqualTo("gpt-4o")
                .jsonPath("$.models[1].requests").isEqualTo(3)
                .jsonPath("$.models[1].inputTokens").isEqualTo(1500)
                .jsonPath("$.models[1].share").isEqualTo("0.3571")
                .jsonPath("$.models[2].model").isEqualTo("mystery-model")
                .jsonPath("$.models[2].unpriced").isEqualTo(true)
                .jsonPath("$.models[2].unpricedRequests").isEqualTo(1)
                .jsonPath("$.models[2].share").isEqualTo("0.0000");
    }

    @Test
    void agents() {
        get("/api/projects/{id}/agents?" + RANGE, p).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.agents").value(hasSize(3))
                .jsonPath("$.agents[0].name").isEqualTo("refund-agent")
                .jsonPath("$.agents[0].source").isEqualTo("header")
                .jsonPath("$.agents[0].requests").isEqualTo(2)
                .jsonPath("$.agents[0].inputTokens").isEqualTo(2100)
                .jsonPath("$.agents[0].cacheReadTokens").isEqualTo(500)
                .jsonPath("$.agents[0].costUsd").isEqualTo("1.35000000")
                .jsonPath("$.agents[0].models[0].model").isEqualTo("claude-sonnet-5-5")
                .jsonPath("$.agents[0].models[0].share").isEqualTo("1.0000")
                // Monthly budget 2.00: spent 1.35, projected 1.35 × 30 / 19.5, crosses 80 % (1.60) on Nov 24 IST.
                .jsonPath("$.agents[0].budgets[0].limitUsd").isEqualTo("2.00")
                .jsonPath("$.agents[0].budgets[0].spentUsd").isEqualTo("1.35000000")
                .jsonPath("$.agents[0].budgets[0].usedFraction").isEqualTo("0.6750")
                .jsonPath("$.agents[0].budgets[0].projectedUsd").isEqualTo("2.07692308")
                .jsonPath("$.agents[0].budgets[0].projectedAlertDate").isEqualTo("2026-11-24")
                .jsonPath("$.agents[1].name").isEqualTo("summary-agent")
                .jsonPath("$.agents[1].source").isEqualTo("path")
                .jsonPath("$.agents[1].requests").isEqualTo(3)
                .jsonPath("$.agents[1].costUsd").isEqualTo("0.75000000")
                .jsonPath("$.agents[1].unpricedRequests").isEqualTo(1)
                .jsonPath("$.agents[1].models[*].model").value(contains("gpt-4o", "mystery-model"))
                .jsonPath("$.agents[1].budgets").value(hasSize(0))
                .jsonPath("$.agents[2].name").isEqualTo("agent-ffff")
                .jsonPath("$.agents[2].source").isEqualTo("fingerprint")
                .jsonPath("$.agents[2].errors").isEqualTo(1)
                .jsonPath("$.agents[2].firstSeen").exists()
                .jsonPath("$.agents[2].lastSeen").exists();
    }

    @Test
    void agentsWithoutTrafficInTheRangeStillAppear() {
        UUID idle = agent(p, "idle-agent", "path");
        get("/api/projects/{id}/agents?from=2026-01-01&to=2026-01-02", p).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.agents").value(hasSize(4))
                .jsonPath("$.agents[?(@.id == '" + idle + "')].costUsd").isEqualTo("0.00000000");
    }

    @Test
    void runsNewestFirstWithDuration() {
        get("/api/projects/{id}/runs?" + RANGE, p).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.runs").value(hasSize(2))
                .jsonPath("$.nextCursor").value(nullValue())
                .jsonPath("$.runs[0].runId").isEqualTo("r2")
                .jsonPath("$.runs[0].incomplete").isEqualTo(1)
                .jsonPath("$.runs[1].runId").isEqualTo("r1")
                .jsonPath("$.runs[1].calls").isEqualTo(2)
                .jsonPath("$.runs[1].costUsd").isEqualTo("1.75000000")
                // first call started 10:00:00 - 1 s, last ended 10:00:03
                .jsonPath("$.runs[1].startedAt").isEqualTo("2026-11-18T09:59:59Z")
                .jsonPath("$.runs[1].endedAt").isEqualTo("2026-11-18T10:00:03Z")
                .jsonPath("$.runs[1].durationMs").isEqualTo(4000)
                .jsonPath("$.runs[1].agents[*].name").value(contains("refund-agent", "summary-agent"));
    }

    @Test
    void runDetailGivesWaterfallOffsets() {
        get("/api/projects/{id}/runs/{run}", p, "r1").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.calls").isEqualTo(2)
                .jsonPath("$.durationMs").isEqualTo(4000)
                .jsonPath("$.costUsd").isEqualTo("1.75000000")
                .jsonPath("$.models").value(contains("gpt-4o", "claude-sonnet-5-5"))
                .jsonPath("$.agents[0].name").isEqualTo("refund-agent")
                .jsonPath("$.agents[0].costUsd").isEqualTo("1.25000000")
                .jsonPath("$.events[0].index").isEqualTo(1)
                .jsonPath("$.events[0].agentName").isEqualTo("summary-agent")
                .jsonPath("$.events[0].offsetMs").isEqualTo(0)
                .jsonPath("$.events[0].latencyMs").isEqualTo(1000)
                .jsonPath("$.events[1].agentName").isEqualTo("refund-agent")
                .jsonPath("$.events[1].offsetMs").isEqualTo(2000)
                .jsonPath("$.events[1].cacheReadTokens").isEqualTo(500)
                .jsonPath("$.truncated").isEqualTo(false);

        get("/api/projects/{id}/runs/{run}", p, "no-such-run").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.error.code").isEqualTo("not_found");
        get("/api/projects/{id}/runs/{run}", p, "x".repeat(129)).exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void runsArePagedFiftyAtATimeWithACursor() {
        UUID q = project("paging");
        UUID agent = agent(q, "worker", "header");
        Instant base = Instant.parse("2026-11-19T00:00:00Z");
        for (int i = 0; i < 120; i++) {
            // Two runs share each timestamp, so the run id must break ties in the cursor.
            event(q, agent, String.format("run-%03d", i), "openai", "gpt-4o", 1, 1, 0, "0.01", 200, false, 10,
                    base.plusSeconds(i / 2));
        }
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            String uri = "/api/projects/" + q + "/runs?" + RANGE + (cursor == null ? "" : "&cursor=" + cursor);
            Map<?, ?> page = get(uri).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
                    .getResponseBody();
            List<?> runs = (List<?>) page.get("runs");
            runs.forEach(r -> seen.add((String) ((Map<?, ?>) r).get("runId")));
            cursor = (String) page.get("nextCursor");
            pages++;
            assertThat(runs).hasSize(pages < 3 ? 50 : 20);
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        Set<String> unique = new HashSet<>(seen);
        assertThat(unique).hasSize(120);
        assertThat(seen.get(0)).isEqualTo("run-119");
        assertThat(seen.get(119)).isEqualTo("run-000");

        get("/api/projects/{id}/runs?cursor=not-a-cursor", q).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.message").isEqualTo("cursor is not valid");
    }

    @Test
    void pagingAcrossManyInterleavedRunsMatchesAPlainOrdering() {
        // 3,000 runs of 3 calls whose calls interleave in time, so the backwards walk crosses several chunks and
        // meets runs part-way through; ties on the end time are common.
        UUID q = project("interleaved");
        UUID agent = agent(q, "worker", "header");
        jdbc.update("""
                INSERT INTO usage_event (project_id, agent_id, run_id, provider, endpoint, model, cost_usd, status_code,
                                         latency_ms, created_at)
                SELECT ?, ?, 'run-' || (n % 3000), 'openai', 'chat.completions', 'gpt-4o', 0.001, 200, 5,
                       TIMESTAMPTZ '2026-11-19T00:00:00Z' + (n / 7) * interval '1 second'
                FROM generate_series(0, 8999) AS n
                """, q, agent);
        List<String> expected = jdbc.queryForList("""
                SELECT run_id FROM usage_event WHERE project_id = ? GROUP BY run_id
                ORDER BY max(created_at) DESC, run_id DESC
                """, String.class, q);

        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            Map<?, ?> page = get("/api/projects/" + q + "/runs?" + RANGE + (cursor == null ? "" : "&cursor=" + cursor))
                    .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
            ((List<?>) page.get("runs")).forEach(r -> {
                Map<?, ?> run = (Map<?, ?>) r;
                seen.add((String) run.get("runId"));
                assertThat(run.get("calls")).isEqualTo(3);
            });
            cursor = (String) page.get("nextCursor");
        } while (cursor != null);

        assertThat(seen).hasSize(3000).isEqualTo(expected);
    }

    @Test
    void unknownProjectIs404AndBadIdIs400() {
        UUID unknown = UUID.randomUUID();
        for (String path : List.of("summary", "timeseries", "models", "agents", "runs", "runs/r1", "budgets", "alerts",
                "events.csv")) {
            get("/api/projects/" + unknown + "/" + path).exchange()
                    .expectStatus().isNotFound()
                    .expectBody().jsonPath("$.error.code").isEqualTo("not_found");
        }
        get("/api/projects/not-a-uuid/summary").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.code").isEqualTo("invalid_request");
    }

    @Test
    void moneyIsAlwaysAString() {
        Map<?, ?> summary = get("/api/projects/{id}/summary?" + RANGE, p).exchange().expectBody(Map.class)
                .returnResult().getResponseBody();
        assertThat(summary.get("costUsd")).isInstanceOf(String.class);
        assertThat(new BigDecimal((String) summary.get("costUsd"))).isEqualByComparingTo("2.1");
    }
}
