package dev.llmcosttracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Milestone 1: the app boots against Postgres 16, Flyway creates the schema, health is public and UP. */
@AutoConfigureWebTestClient(timeout = "PT30S") // first request on a cold JVM can exceed the 5 s default on slow CI hosts
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "tracker.admin-password=test-admin-password")
class SkeletonIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    WebTestClient web;

    @Test
    void flywayCreatesAllSevenTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'",
                String.class);

        assertThat(tables).containsExactlyInAnyOrder(
                "project", "agent", "agent_alias", "model_price", "usage_event", "budget", "alert_event");
    }

    @Test
    void usageEventIndexesExist() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'usage_event' AND indexname LIKE 'ue\\_%'",
                String.class);

        assertThat(indexes).containsExactlyInAnyOrder("ue_project_time", "ue_agent_time", "ue_run", "ue_unpriced");
    }

    @Test
    void projectBudgetIsUniqueEvenThoughAgentIsNull() {
        String projectId = "00000000-0000-0000-0000-000000000001";
        jdbc.update("INSERT INTO project (id, name, key_hash, key_last4) VALUES (?::uuid, 'p', 'h', 'abcd')", projectId);
        String insertBudget = "INSERT INTO budget (id, project_id, agent_id, period, limit_usd) "
                + "VALUES (gen_random_uuid(), ?::uuid, NULL, 'day', 10)";
        try {
            jdbc.update(insertBudget, projectId);
            assertThatThrownBy(() -> jdbc.update(insertBudget, projectId)).hasMessageContaining("duplicate key");
        } finally {
            jdbc.update("DELETE FROM project WHERE id = ?::uuid", projectId);
        }
    }

    @Test
    void healthEndpointsReportUp() {
        for (String path : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")) {
            web.get().uri(path).exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.status").isEqualTo("UP");
        }
    }
}
