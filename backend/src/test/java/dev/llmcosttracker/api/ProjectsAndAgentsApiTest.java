package dev.llmcosttracker.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;

import dev.llmcosttracker.auth.ProjectKeyService;
import dev.llmcosttracker.auth.ProjectKeys;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Projects (create, list, rotate key) and agents (rename, merge). */
class ProjectsAndAgentsApiTest extends ApiTestBase {

    @Autowired
    ProjectKeyService keys;

    @Test
    void createReturnsTheFullKeyOnceAndListShowsItMasked() {
        Map<?, ?> created = post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "  support-bot  ")).exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        String key = (String) created.get("key");
        assertThat(key).startsWith("trk_").hasSizeGreaterThan(40);
        assertThat(created.get("name")).isEqualTo("support-bot");
        assertThat(created.get("keyMasked")).isEqualTo("trk_…" + key.substring(key.length() - 4));

        // Only the hash is stored.
        assertThat(jdbc.queryForObject("SELECT key_hash FROM project WHERE name = 'support-bot'", String.class))
                .isEqualTo(ProjectKeys.hash(key));
        String listed = get("/api/projects").exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(listed).contains("support-bot").contains((String) created.get("keyMasked")).doesNotContain(key);
    }

    @Test
    void createValidatesTheName() {
        post("/api/projects").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("name", " ")).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.code").isEqualTo("validation_error");
        post("/api/projects").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("name", "x".repeat(101)))
                .exchange().expectStatus().isBadRequest();
        post("/api/projects").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("name", "a\u0007b"))
                .exchange().expectStatus().isBadRequest();
        post("/api/projects").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"name\":\"p\",\"owner\":\"me\"}")
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.message").isEqualTo("Unknown field 'owner'");
        post("/api/projects").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"name\":").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.code").isEqualTo("invalid_request");
        post("/api/projects").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("name", "dup")).exchange()
                .expectStatus().isCreated();
        post("/api/projects").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("name", "dup")).exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error.code").isEqualTo("conflict");
    }

    @Test
    void rotateKeyReturnsANewKeyAndTheOldOneStopsWorkingAtOnce() {
        Map<?, ?> created = post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "rotating")).exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        String oldKey = (String) created.get("key");
        UUID id = UUID.fromString((String) created.get("id"));
        assertThat(keys.resolve(oldKey).block()).isEqualTo(id); // now cached by the proxy's key service

        Map<?, ?> rotated = post("/api/projects/{id}/rotate-key", id).exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        String newKey = (String) rotated.get("key");

        assertThat(newKey).startsWith("trk_").isNotEqualTo(oldKey);
        assertThat(keys.resolve(oldKey).blockOptional()).isEmpty();
        assertThat(keys.resolve(newKey).block()).isEqualTo(id);
        // And through the proxy itself: the old key is refused before anything is forwarded.
        web.post().uri("/proxy/openai/chat/completions").header("X-Tracker-Key", oldKey)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isUnauthorized();

        post("/api/projects/{id}/rotate-key", UUID.randomUUID()).exchange().expectStatus().isNotFound();
    }

    @Test
    void renameSetsAndClearsTheDisplayNameButNeverTheSlug() {
        UUID p = project("p");
        UUID a = agent(p, "agent-a3f9", "fingerprint");

        patch("/api/agents/{id}", a).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("displayName", "  Email drafter ")).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("agent-a3f9")
                .jsonPath("$.displayName").isEqualTo("Email drafter");
        patch("/api/agents/{id}", a).contentType(MediaType.APPLICATION_JSON).bodyValue("{\"displayName\":null}")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.displayName").doesNotExist();

        patch("/api/agents/{id}", a).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("displayName", "x".repeat(101))).exchange().expectStatus().isBadRequest();
        patch("/api/agents/{id}", a).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("displayName", "ok", "name", "hacked")).exchange().expectStatus().isBadRequest();
        patch("/api/agents/{id}", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("displayName", "x")).exchange().expectStatus().isNotFound();
        assertThat(jdbc.queryForObject("SELECT name FROM agent WHERE id = ?", String.class, a)).isEqualTo("agent-a3f9");
    }

    @Test
    void mergeMovesEventsAndAddsAliases() {
        UUID p = project("p");
        UUID from = agent(p, "agent-ffff", "fingerprint");
        UUID into = agent(p, "email-agent", "header");
        event(p, from, null, "openai", "gpt-4o", 1, 1, 0, "0.01", 200, false, 10, Instant.parse("2026-11-19T00:00:00Z"));

        post("/api/agents/{id}/merge", from).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("intoAgentId", into.toString())).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.id").isEqualTo(into.toString());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent WHERE id = ?", Long.class, from)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_event WHERE agent_id = ?", Long.class, into))
                .isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT alias FROM agent_alias WHERE agent_id = ?", String.class, into))
                .containsExactlyInAnyOrder("agent-ffff", "fp:" + "f".repeat(64));
        get("/api/projects/{id}/agents", p).exchange().expectBody().jsonPath("$.agents").value(hasSize(1));
    }

    @Test
    void mergeValidatesItsInput() {
        UUID p = project("p");
        UUID q = project("q");
        UUID a = agent(p, "a", "header");
        UUID other = agent(q, "b", "header");

        post("/api/agents/{id}/merge", a).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("intoAgentId", a.toString())).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.message").isEqualTo("Cannot merge an agent into itself");
        post("/api/agents/{id}/merge", a).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("intoAgentId", other.toString())).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.message").isEqualTo("Agents belong to different projects");
        post("/api/agents/{id}/merge", a).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("intoAgentId", UUID.randomUUID().toString())).exchange()
                .expectStatus().isNotFound();
        post("/api/agents/{id}/merge", a).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("intoAgentId", "not-a-uuid")).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.message").isEqualTo("intoAgentId must be a UUID");
        post("/api/agents/{id}/merge", a).contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.message").isEqualTo("intoAgentId is required");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent", Long.class)).isEqualTo(2);
    }
}
