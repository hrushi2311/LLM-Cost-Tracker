package dev.llmcosttracker.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Price list (with unpriced models), adding a price, and recompute. */
class PricesApiTest extends ApiTestBase {

    private static Map<String, Object> price(String provider, String model, String from, Object in, Object out) {
        Map<String, Object> m = new HashMap<>();
        m.put("provider", provider);
        m.put("model", model);
        m.put("effectiveFrom", from);
        m.put("inputPerMtok", in);
        m.put("outputPerMtok", out);
        return m;
    }

    @Test
    void addingAPriceAndRecomputingFillsUnpricedEvents() {
        UUID p = project("p");
        UUID a = agent(p, "a", "header");
        Instant at = Instant.parse("2026-11-19T10:00:00Z");
        event(p, a, null, "openai", "new-model-2026-11-01", 1_000_000, 100_000, 0, null, 200, false, 10, at);
        event(p, a, null, "openai", "new-model-2026-11-01", 500_000, 0, 0, null, 200, false, 10, at);
        event(p, a, null, "anthropic", "other-model", 10, 10, 0, null, 200, false, 10, at);

        get("/api/prices").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.prices").value(hasSize(0))
                .jsonPath("$.unpriced").value(hasSize(2))
                .jsonPath("$.unpriced[0].model").isEqualTo("new-model-2026-11-01")
                .jsonPath("$.unpriced[0].events").isEqualTo(2);

        Map<String, Object> body = price("openai", "new-model", "2026-11-01", "1.5", "6.000001");
        body.put("cacheReadPerMtok", "0.75");
        post("/api/prices").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.source").isEqualTo("user")
                .jsonPath("$.inputPerMtok").isEqualTo("1.500000")
                .jsonPath("$.outputPerMtok").isEqualTo("6.000001")
                .jsonPath("$.cacheWritePerMtok").doesNotExist();

        // The dated snapshot matches the "new-model" prefix row.
        post("/api/prices/recompute").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.examined").isEqualTo(3)
                .jsonPath("$.updated").isEqualTo(2)
                .jsonPath("$.stillUnpriced").isEqualTo(1);
        List<BigDecimal> costs = jdbc.queryForList(
                "SELECT cost_usd FROM usage_event WHERE model = 'new-model-2026-11-01' ORDER BY input_tokens",
                BigDecimal.class);
        // 0.5M × 1.5 = 0.75 ; 1M × 1.5 + 0.1M × 6.000001 = 1.5 + 0.6000001
        assertThat(costs.get(0)).isEqualByComparingTo("0.75");
        assertThat(costs.get(1)).isEqualByComparingTo("2.1000001");

        get("/api/prices").exchange().expectBody()
                .jsonPath("$.prices").value(hasSize(1))
                .jsonPath("$.unpriced").value(hasSize(1))
                .jsonPath("$.unpriced[0].model").isEqualTo("other-model");
    }

    @Test
    void recomputeAllRepricesEveryEventInTheRange() {
        UUID p = project("p");
        UUID a = agent(p, "a", "header");
        jdbc.update("INSERT INTO model_price (provider, model, effective_from, input_per_mtok, output_per_mtok) "
                + "VALUES ('openai', 'gpt-4o', DATE '2026-01-01', 1, 1)");
        event(p, a, null, "openai", "gpt-4o", 1_000_000, 0, 0, "9.99", 200, false, 10,
                Instant.parse("2026-11-19T10:00:00Z")); // priced wrongly
        event(p, a, null, "openai", "gpt-4o", 1_000_000, 0, 0, "9.99", 200, false, 10,
                Instant.parse("2026-10-01T10:00:00Z")); // outside the range: untouched

        post("/api/prices/recompute?all=true&from=2026-11-01&to=2026-11-20").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.examined").isEqualTo(1)
                .jsonPath("$.updated").isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT cost_usd FROM usage_event ORDER BY created_at", BigDecimal.class))
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("9.99"), new BigDecimal("1"));
    }

    @Test
    void addValidation() {
        List<Map<String, Object>> bad = List.of(
                price("gemini", "m", "2026-11-01", "1", "1"),          // not a configured provider
                price("openai", "", "2026-11-01", "1", "1"),
                price("openai", "has space", "2026-11-01", "1", "1"),
                price("openai", "m", null, "1", "1"),
                price("openai", "m", "2026-11-01", "-1", "1"),
                price("openai", "m", "2026-11-01", "1", null),
                price("openai", "m", "2026-11-01", "1.0000001", "1"),
                price("openai", "m", "2026-11-01", "1000000", "1"));
        for (Map<String, Object> b : bad) {
            post("/api/prices").contentType(MediaType.APPLICATION_JSON).bodyValue(b).exchange()
                    .expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.error.code").isEqualTo("validation_error");
        }
        post("/api/prices").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(price("openai", "m", "11/01/2026", "1", "1")).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.message").isEqualTo("Invalid value for 'effectiveFrom'");
        Map<String, Object> extra = price("openai", "m", "2026-11-01", "1", "1");
        extra.put("source", "seed");
        post("/api/prices").contentType(MediaType.APPLICATION_JSON).bodyValue(extra).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.message").isEqualTo("Unknown field 'source'");

        post("/api/prices").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(price("openai", "m", "2026-11-01", "1", "1")).exchange().expectStatus().isCreated();
        post("/api/prices").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(price("openai", "m", "2026-11-01", "2", "2")).exchange()
                .expectStatus().isEqualTo(409);
        post("/api/prices/recompute?all=true&from=2026-11-02&to=2026-11-01").exchange()
                .expectStatus().isBadRequest();
        post("/api/prices/recompute?all=maybe").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.code").isEqualTo("invalid_request");
    }
}
