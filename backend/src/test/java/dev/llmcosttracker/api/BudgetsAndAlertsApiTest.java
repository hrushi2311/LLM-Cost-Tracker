package dev.llmcosttracker.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Budget CRUD with validation, and the alerts list. */
class BudgetsAndAlertsApiTest extends ApiTestBase {

    UUID p;
    UUID agent;

    /** 2026-11-10 12:00 in Kolkata; the day started at 2026-11-09T18:30Z. */
    @Override
    protected Instant testNow() {
        return Instant.parse("2026-11-10T06:30:00Z");
    }

    @BeforeEach
    void data() {
        p = project("p");
        agent = agent(p, "refund-agent", "header");
        event(p, agent, null, "openai", "gpt-4o", 1, 1, 0, "3.00", 200, false, 10, Instant.parse("2026-11-09T20:00:00Z"));
        event(p, agent, null, "openai", "gpt-4o", 1, 1, 0, "1.00", 200, false, 10, Instant.parse("2026-11-09T17:00:00Z"));
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void createReadUpdateDelete() {
        Map<?, ?> created = post("/api/projects/{p}/budgets", p).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body("agentId", agent.toString(), "period", "day", "limitUsd", "5.00")).exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        String id = (String) created.get("id");

        // Today in Kolkata only: the 17:00Z event was still yesterday there.
        get("/api/projects/{p}/budgets/{id}", p, id).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.agentId").isEqualTo(agent.toString())
                .jsonPath("$.period").isEqualTo("day")
                .jsonPath("$.limitUsd").isEqualTo("5.00")
                .jsonPath("$.alertPct").isEqualTo(80)
                .jsonPath("$.enforce").isEqualTo(false)
                .jsonPath("$.periodStart").isEqualTo("2026-11-10")
                .jsonPath("$.periodEnd").isEqualTo("2026-11-10")
                .jsonPath("$.spentUsd").isEqualTo("3.00000000")
                .jsonPath("$.usedFraction").isEqualTo("0.6000")
                // 3.00 in 12 h → 6.00 by midnight; 80 % (4.00) reached at 16 h → still today
                .jsonPath("$.projectedUsd").isEqualTo("6.00000000")
                .jsonPath("$.projectedAlertDate").isEqualTo("2026-11-10");

        put("/api/projects/{p}/budgets/{id}", p, id).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body("agentId", agent.toString(), "period", "month", "limitUsd", 250, "alertPct", 90,
                        "enforce", true)).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.period").isEqualTo("month")
                .jsonPath("$.limitUsd").isEqualTo("250.00")
                .jsonPath("$.alertPct").isEqualTo(90)
                .jsonPath("$.enforce").isEqualTo(true)
                .jsonPath("$.spentUsd").isEqualTo("4.00000000");

        get("/api/projects/{p}/budgets", p).exchange().expectStatus().isOk().expectBody().jsonPath("$").value(hasSize(1));
        delete("/api/projects/{p}/budgets/{id}", p, id).exchange().expectStatus().isNoContent();
        delete("/api/projects/{p}/budgets/{id}", p, id).exchange().expectStatus().isNotFound();
        get("/api/projects/{p}/budgets/{id}", p, id).exchange().expectStatus().isNotFound();
    }

    @Test
    void projectWideBudgetHasNoAgentAndIsUniquePerPeriod() {
        post("/api/projects/{p}/budgets", p).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body("period", "month", "limitUsd", "100")).exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.agentId").value(nullValue());
        post("/api/projects/{p}/budgets", p).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body("period", "month", "limitUsd", "200")).exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error.code").isEqualTo("conflict");
        post("/api/projects/{p}/budgets", p).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body("period", "day", "limitUsd", "10")).exchange()
                .expectStatus().isCreated();
    }

    @Test
    void validation() {
        UUID otherProjectAgent = agent(project("other"), "x", "header");
        Object[][] bad = {
            {"period", "week", "limitUsd", "5"},
            {"limitUsd", "5"},
            {"period", "day"},
            {"period", "day", "limitUsd", "0"},
            {"period", "day", "limitUsd", "-1"},
            {"period", "day", "limitUsd", "1.234"},
            {"period", "day", "limitUsd", "10000000000"},
            {"period", "day", "limitUsd", "5", "alertPct", 0},
            {"period", "day", "limitUsd", "5", "alertPct", 101},
            {"period", "day", "limitUsd", "5", "agentId", "nope"},
            {"period", "day", "limitUsd", "5", "agentId", otherProjectAgent.toString()},
        };
        for (Object[] b : bad) {
            post("/api/projects/{p}/budgets", p).contentType(MediaType.APPLICATION_JSON).bodyValue(body(b)).exchange()
                    .expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.error.code").isEqualTo("validation_error");
        }
        post("/api/projects/{p}/budgets", p).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"period\":\"day\",\"limitUsd\":\"abc\"}").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.message").isEqualTo("Invalid value for 'limitUsd'");
        post("/api/projects/{p}/budgets", p).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body("period", "day", "limitUsd", "5", "currency", "EUR")).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.message").isEqualTo("Unknown field 'currency'");
        put("/api/projects/{p}/budgets/{id}", p, UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body("period", "day", "limitUsd", "5")).exchange()
                .expectStatus().isNotFound();
        get("/api/projects/{p}/budgets", p).exchange().expectBody().jsonPath("$").value(hasSize(0));
    }

    @Test
    void alertsListsRecentAlertEventsForTheProject() {
        UUID budget = UUID.randomUUID();
        jdbc.update("INSERT INTO budget (id, project_id, agent_id, period, limit_usd) VALUES (?, ?, ?, 'month', 200)",
                budget, p, agent);
        jdbc.update("INSERT INTO alert_event (budget_id, period_start, kind, spent_usd, created_at) "
                + "VALUES (?, DATE '2026-11-01', 'threshold', 160.25, TIMESTAMPTZ '2026-11-08T10:00:00Z')", budget);
        jdbc.update("INSERT INTO alert_event (budget_id, period_start, kind, spent_usd, created_at) "
                + "VALUES (?, DATE '2026-11-01', 'exceeded', 201.00, TIMESTAMPTZ '2026-11-09T10:00:00Z')", budget);
        UUID other = project("other");
        UUID otherBudget = UUID.randomUUID();
        jdbc.update("INSERT INTO budget (id, project_id, period, limit_usd) VALUES (?, ?, 'day', 1)", otherBudget, other);
        jdbc.update("INSERT INTO alert_event (budget_id, period_start, kind, spent_usd) "
                + "VALUES (?, DATE '2026-11-10', 'exceeded', 1.5)", otherBudget);

        get("/api/projects/{p}/alerts", p).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$").value(hasSize(2))
                .jsonPath("$[0].kind").isEqualTo("exceeded")
                .jsonPath("$[0].spentUsd").isEqualTo("201.00")
                .jsonPath("$[0].limitUsd").isEqualTo("200.00")
                .jsonPath("$[0].agentName").isEqualTo("refund-agent")
                .jsonPath("$[0].periodStart").isEqualTo("2026-11-01")
                .jsonPath("$[1].kind").isEqualTo("threshold");
    }
}
