package dev.llmcosttracker.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.llmcosttracker.demo.DemoSeeder;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DuplicateKeyException;

/** CSV export, /api/status, and the --seed-demo data set. */
class ExportStatusAndDemoApiTest extends ApiTestBase {

    @Autowired
    DemoSeeder seeder;

    @Override
    protected Instant testNow() {
        return Instant.parse("2026-11-20T06:30:00Z");
    }

    @Test
    void csvExportStreamsTheRangeWithFormulaInjectionDefused() {
        UUID p = project("Support Bot");
        UUID a = agent(p, "refund-agent", "header");
        event(p, a, "=HYPERLINK(\"http://evil\",\"x\")", "openai", "gpt-4o", 10, 5, 2, "0.00012345", 200, false, 321,
                Instant.parse("2026-11-19T10:00:00Z"));
        event(p, a, null, "anthropic", "claude-sonnet-5-5", 1, 1, 0, null, 529, false, 50,
                Instant.parse("2026-11-19T11:00:00Z"));
        event(p, a, null, "openai", "gpt-4o", 1, 1, 0, "1", 200, false, 1, Instant.parse("2026-10-01T00:00:00Z"));
        jdbc.update("UPDATE usage_event SET tags = '{\"env\":\"prod\"}'::jsonb WHERE status_code = 529");

        var result = get("/api/projects/{p}/events.csv?from=2026-11-19T00:00:00Z&to=2026-11-20T00:00:00Z", p)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("text/csv;charset=UTF-8")
                .expectHeader().valueMatches("Content-Disposition",
                        "attachment; filename=\"events-support-bot-2026-11-19-2026-11-20.csv\"")
                .expectBody(String.class).returnResult();
        String[] lines = result.getResponseBody().split("\r\n");

        assertThat(lines).hasSize(3); // header + 2 rows in the range
        assertThat(lines[0]).startsWith("created_at,agent,agent_source,run_id,provider");
        assertThat(lines[1]).isEqualTo("2026-11-19T10:00:00Z,refund-agent,header,"
                + "\"'=HYPERLINK(\"\"http://evil\"\",\"\"x\"\")\",openai,chat.completions,gpt-4o,10,5,2,0,0,"
                + "0.00012345,false,false,200,321,");
        assertThat(lines[2]).isEqualTo("2026-11-19T11:00:00Z,refund-agent,header,,anthropic,chat.completions,"
                + "claude-sonnet-5-5,1,1,0,0,0,,false,false,529,50,\"{\"\"env\"\": \"\"prod\"\"}\"");
    }

    @Test
    void csvExportOfALargeRangeStreamsEveryRow() {
        UUID p = project("big");
        UUID a = agent(p, "a", "header");
        jdbc.update("""
                INSERT INTO usage_event (project_id, agent_id, provider, endpoint, model, status_code, latency_ms,
                                         cost_usd, created_at)
                SELECT ?, ?, 'openai', 'chat.completions', 'gpt-4o', 200, 5, 0.001,
                       TIMESTAMPTZ '2026-11-10T00:00:00Z' + n * interval '1 second'
                FROM generate_series(1, 20000) AS n
                """, p, a);
        // The test client buffers 256 KB by default; the server streams, the client here just reads it all.
        String body = web.mutate().codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)).build()
                .get().uri("/api/projects/{p}/events.csv?from=2026-11-01&to=2026-11-20", p)
                .cookie("tracker_session", sessionCookie).exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body.split("\r\n")).hasSize(20_001);
    }

    @Test
    void statusReportsVersionLastEventDropsAndQueueDepth() {
        UUID p = project("p");
        UUID a = agent(p, "a", "header");
        event(p, a, null, "openai", "gpt-4o", 1, 1, 0, "0.1", 200, false, 1, Instant.parse("2026-11-19T10:00:00Z"));

        get("/api/status").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.version").isEqualTo("0.1.0-SNAPSHOT")
                .jsonPath("$.lastEventAt").isEqualTo("2026-11-19T10:00:00Z")
                .jsonPath("$.eventsDropped").isNumber()
                .jsonPath("$.queueDepth").isEqualTo(0)
                .jsonPath("$.queueCapacity").isEqualTo(10000)
                .jsonPath("$.timezone").isEqualTo("Asia/Kolkata");
        get("/api/status?projectId={p}", UUID.randomUUID()).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.lastEventAt").doesNotExist();
        get("/api/status?projectId=nope").exchange().expectStatus().isBadRequest();
    }

    @Test
    void demoSeedHasFourAgentsFourModelsRunsErrorsAndIncompleteStreams() {
        DemoSeeder.Result r = seeder.seed("demo", 5_000, 30, 42L);

        assertThat(r.events()).isBetween(5_000L, 5_015L);
        assertThat(r.key()).startsWith("trk_");
        assertThat(jdbc.queryForList("SELECT source FROM agent WHERE project_id = ? ORDER BY name", String.class,
                r.projectId())).containsExactlyInAnyOrder("path", "header", "path", "fingerprint");
        assertThat(jdbc.queryForList("SELECT DISTINCT model FROM usage_event WHERE project_id = ?", String.class,
                r.projectId())).containsExactlyInAnyOrder("gpt-4o", "gpt-4o-mini", "claude-sonnet-5-5",
                "claude-haiku-5-5");
        Map<String, Object> stats = jdbc.queryForMap("""
                SELECT count(DISTINCT run_id) AS runs, count(*) FILTER (WHERE status_code >= 400) AS errors,
                       count(*) FILTER (WHERE incomplete) AS incomplete, count(*) FILTER (WHERE run_id IS NULL) AS solo,
                       count(*) FILTER (WHERE cost_usd IS NULL) AS unpriced,
                       min(created_at) AS first, max(created_at) AS last
                FROM usage_event WHERE project_id = ?
                """, r.projectId());
        assertThat((Long) stats.get("runs")).isGreaterThan(300);
        assertThat((Long) stats.get("errors")).isBetween(5L, 60L);
        assertThat((Long) stats.get("incomplete")).isBetween(5L, 120L);
        assertThat((Long) stats.get("solo")).isGreaterThan(200); // calls outside any run
        assertThat((Long) stats.get("unpriced")).isZero(); // illustrative rates cover all four models
        // The fingerprint agent first shows up a week in, so the dashboard has a "new agent" moment.
        Instant fpFirst = jdbc.queryForObject("SELECT first_seen FROM agent WHERE project_id = ? AND source = 'fingerprint'",
                java.sql.Timestamp.class, r.projectId()).toInstant();
        assertThat(fpFirst).isAfter(Instant.parse("2026-10-27T00:00:00Z"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM budget WHERE project_id = ?", Long.class, r.projectId()))
                .isEqualTo(4);

        // And the API serves it.
        get("/api/projects/{p}/summary?from=2026-10-21T06:30:00Z&to=2026-11-20T06:30:01Z", r.projectId()).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.requests").isEqualTo((int) r.events());
    }

    @Test
    void seedDemoCommandRefusesAnExistingName() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        var command = newCommand(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        command.run(new DefaultApplicationArguments("--seed-demo=shots", "--seed-demo-events=300"));
        assertThat(command.getExitCode()).isZero();
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("Seeded demo project \"shots\"").contains("trk_");

        bytes.reset();
        command.run(new DefaultApplicationArguments("--seed-demo=shots"));
        assertThat(command.getExitCode()).isEqualTo(1);
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("already exists");

        var bad = newCommand(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        bad.run(new DefaultApplicationArguments("--seed-demo", "--seed-demo-events=lots"));
        assertThat(bad.getExitCode()).isEqualTo(2);

        // Without the flag nothing happens (it never runs on a normal start).
        var idle = newCommand(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        long before = jdbc.queryForObject("SELECT count(*) FROM project", Long.class);
        idle.run(new DefaultApplicationArguments());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project", Long.class)).isEqualTo(before);
    }

    @Test
    void seedingTwiceWithTheSameNameFailsCleanly() {
        seeder.seed("twice", 100, 30, 1L);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> seeder.seed("twice", 100, 30, 1L))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project WHERE name = 'twice'", Long.class)).isEqualTo(1);
    }

    private dev.llmcosttracker.demo.SeedDemoCommand newCommand(PrintStream out) {
        return new dev.llmcosttracker.demo.SeedDemoCommand(seeder, out);
    }
}
