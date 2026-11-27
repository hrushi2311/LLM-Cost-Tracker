package dev.llmcosttracker.attribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.llmcosttracker.Await;
import dev.llmcosttracker.proxy.ProxyIntegrationTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** Agent creation under concurrency, fingerprint name collisions, aliases, and transactional merge. */
class AgentStoreIntegrationTest extends ProxyIntegrationTestBase {

    @Autowired
    AgentRepository agents;

    @Autowired
    AgentService agentService;

    @Autowired
    TransactionTemplate tx;

    private UUID resolveInTx(AgentRef ref) {
        return tx.execute(s -> agents.resolve(projectId, ref, Instant.now()));
    }

    private long agentRows(String whereName) {
        return jdbc.queryForObject("SELECT count(*) FROM agent WHERE project_id = ? AND name = ?",
                Long.class, projectId, whereName);
    }

    /** Runs {@code task} on {@code threads} threads released at the same instant; returns the distinct results. */
    private Set<UUID> race(int threads, Callable<UUID> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<UUID>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            Set<UUID> ids = new HashSet<>();
            for (Future<UUID> f : futures) {
                ids.add(f.get());
            }
            return ids;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void simultaneousFirstSightingsOfANamedAgentCreateExactlyOneRow() throws Exception {
        Set<UUID> ids = race(16, () -> resolveInTx(AgentRef.header("racer")));

        assertThat(ids).hasSize(1);
        assertThat(agentRows("racer")).isEqualTo(1);
    }

    @Test
    void simultaneousFirstSightingsOfAFingerprintCreateExactlyOneRow() throws Exception {
        String hash = "c0ffee" + "0".repeat(58);

        Set<UUID> ids = race(16, () -> resolveInTx(AgentRef.fingerprint(hash)));

        assertThat(ids).hasSize(1);
        assertThat(agentRows("agent-c0ff")).isEqualTo(1);
    }

    @Test
    void twoSimultaneousProxyCallsForANewAgentCreateOneRow() throws Exception {
        OPENAI.enqueue(jsonResponse(200, fixture("openai/chat-completion.json")));
        OPENAI.enqueue(jsonResponse(200, fixture("openai/chat-completion.json")));

        race(2, () -> {
            rawPost("openai", "/chat/completions", "{\"model\":\"gpt-4o\"}",
                    "X-Tracker-Key", projectKey, "X-Tracker-Agent", "twin");
            return UUID.randomUUID();
        });

        Await.until(this::events, rows -> rows.size() == 2, Duration.ofSeconds(15));
        assertThat(agentRows("twin")).isEqualTo(1);
        assertThat(events()).extracting(e -> e.get("agent_id")).containsOnly(
                jdbc.queryForObject("SELECT id FROM agent WHERE project_id = ? AND name = 'twin'", UUID.class, projectId));
    }

    @Test
    void fingerprintNameCollisionsExtendTo6Then8Characters() {
        String first = "a3f9" + "0".repeat(60);
        String second = "a3f9" + "1".repeat(60);          // same 4-char prefix
        String third = "a3f911" + "2".repeat(58);         // same 4- and 6-char prefix as `second`

        UUID a = resolveInTx(AgentRef.fingerprint(first));
        UUID b = resolveInTx(AgentRef.fingerprint(second));
        UUID c = resolveInTx(AgentRef.fingerprint(third));

        assertThat(jdbc.queryForList("SELECT name FROM agent WHERE id IN (?, ?, ?) ORDER BY length(name)",
                String.class, a, b, c)).containsExactly("agent-a3f9", "agent-a3f911", "agent-a3f91122");
        assertThat(resolveInTx(AgentRef.fingerprint(second))).isEqualTo(b); // stable on later sightings
    }

    @Test
    void mergeMovesEventsAddsAliasesAndLaterCallsFollowTheAlias() throws Exception {
        UUID from = resolveInTx(AgentRef.header("alpha"));
        String fp = "beef" + "0".repeat(60);
        UUID fromFp = resolveInTx(AgentRef.fingerprint(fp));
        UUID into = resolveInTx(AgentRef.header("beta"));
        jdbc.update("INSERT INTO usage_event (project_id, agent_id, provider, endpoint, status_code, latency_ms) "
                + "VALUES (?, ?, 'openai', 'chat.completions', 200, 1), (?, ?, 'openai', 'chat.completions', 200, 1)",
                projectId, from, projectId, fromFp);

        agentService.merge(from, into);
        agentService.merge(fromFp, into);

        assertThat(jdbc.queryForList("SELECT agent_id FROM usage_event WHERE project_id = ?", UUID.class, projectId))
                .containsOnly(into);
        assertThat(jdbc.queryForList("SELECT alias || '->' || agent_id FROM agent_alias WHERE project_id = ?",
                String.class, projectId)).containsExactlyInAnyOrder(
                "alpha->" + into, "agent-beef->" + into, "fp:" + fp + "->" + into);
        assertThat(agentRows("alpha")).isZero();
        assertThat(agentRows("agent-beef")).isZero();

        // The alias is checked before lookup: old name and old fingerprint now resolve to the merged-into agent.
        assertThat(resolveInTx(AgentRef.header("alpha"))).isEqualTo(into);
        assertThat(resolveInTx(AgentRef.fingerprint(fp))).isEqualTo(into);
        assertThat(agentRows("alpha")).isZero(); // not re-created

        // End to end through the proxy as well.
        OPENAI.enqueue(jsonResponse(200, fixture("openai/chat-completion.json")));
        rawPost("openai", "/chat/completions", "{\"model\":\"gpt-4o\"}",
                "X-Tracker-Key", projectKey, "X-Tracker-Agent", "Alpha");
        Await.until(this::events, rows -> rows.size() == 3, Duration.ofSeconds(15));
        assertThat(events()).extracting(e -> e.get("agent_id")).containsOnly(into);
    }

    @Test
    void mergeIsOneTransaction() {
        UUID from = resolveInTx(AgentRef.header("undeletable"));
        UUID into = resolveInTx(AgentRef.header("target"));
        jdbc.update("INSERT INTO usage_event (project_id, agent_id, provider, endpoint, status_code, latency_ms) "
                + "VALUES (?, ?, 'openai', 'chat.completions', 200, 1)", projectId, from);
        // Make the last step of the merge (DELETE of the old agent) fail.
        jdbc.execute("CREATE OR REPLACE FUNCTION test_block_delete() RETURNS trigger AS $$ BEGIN "
                + "IF OLD.name = 'undeletable' THEN RAISE EXCEPTION 'blocked by test'; END IF; RETURN OLD; END $$ "
                + "LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER test_block_delete BEFORE DELETE ON agent FOR EACH ROW "
                + "EXECUTE FUNCTION test_block_delete()");
        try {
            assertThatThrownBy(() -> agentService.merge(from, into)).hasMessageContaining("blocked by test");
        } finally {
            jdbc.execute("DROP TRIGGER test_block_delete ON agent");
            jdbc.execute("DROP FUNCTION test_block_delete()");
        }

        // Nothing from the earlier steps survived: no alias, event still on the old agent, old agent still there.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_alias WHERE project_id = ?", Long.class, projectId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT agent_id FROM usage_event WHERE project_id = ?", UUID.class, projectId))
                .isEqualTo(from);
        assertThat(agentRows("undeletable")).isEqualTo(1);
    }

    @Test
    void mergeRejectsSelfOtherProjectsAndUnknownAgents() {
        UUID a = resolveInTx(AgentRef.header("a"));
        UUID other = tx.execute(s -> agents.resolve(
                projects.create("other-" + UUID.randomUUID()).id(), AgentRef.header("a"), Instant.now()));

        assertThatThrownBy(() -> agentService.merge(a, a)).hasMessageContaining("itself");
        assertThatThrownBy(() -> agentService.merge(a, other)).hasMessageContaining("different projects");
        assertThatThrownBy(() -> agentService.merge(a, UUID.randomUUID())).hasMessageContaining("Unknown agent");
        assertThat(agentRows("a")).isEqualTo(1);
    }

    @Test
    void renameSetsDisplayNameAndKeepsTheSlug() {
        UUID id = resolveInTx(AgentRef.header("planner"));

        agentService.rename(id, "  Trip Planner ");

        Map<String, Object> row = jdbc.queryForMap("SELECT name, display_name FROM agent WHERE id = ?", id);
        assertThat(row).containsEntry("name", "planner").containsEntry("display_name", "Trip Planner");
        assertThat(resolveInTx(AgentRef.header("planner"))).isEqualTo(id);
    }
}
