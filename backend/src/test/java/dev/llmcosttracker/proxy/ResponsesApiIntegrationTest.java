package dev.llmcosttracker.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Milestone 3: OpenAI Responses API through the proxy. */
class ResponsesApiIntegrationTest extends ProxyIntegrationTestBase {

    @Test
    void responsesCallIsRelayedByteForByteAndPriced() throws Exception {
        byte[] body = fixture("openai/response.json");
        OPENAI.enqueue(jsonResponse(200, body));

        HttpResponse<byte[]> response = rawPost("openai", "/responses",
                "{\"model\":\"o4-mini\",\"input\":\"Write a short plan\"}",
                "Authorization", "Bearer sk-test-NOT-A-REAL-KEY", "X-Tracker-Key", projectKey);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(body);
        assertThat(OPENAI.takeRequest(5, TimeUnit.SECONDS).getPath()).isEqualTo("/v1/responses");

        Map<String, Object> row = awaitSingleEvent();
        assertThat(row).containsEntry("provider", "openai")
                .containsEntry("endpoint", "responses")
                .containsEntry("model", "o4-mini-2025-04-16")
                .containsEntry("input_tokens", 476)       // 1500 input - 1024 cached
                .containsEntry("cache_read_tokens", 1024)
                .containsEntry("output_tokens", 900)      // includes 640 reasoning
                .containsEntry("reasoning_tokens", 640)
                .containsEntry("cache_write_tokens", 0);
        // o4-mini test price $1 in / $4 out, no cache price -> cache read at input price:
        // 476*1 + 900*4 + 1024*1 = 476 + 3600 + 1024 = 5100 -> 0.0051
        assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo("0.00510000");
    }

    @Test
    void retrievingAStoredResponseIsForwardedButNotRecorded() throws Exception {
        OPENAI.enqueue(jsonResponse(200, fixture("openai/response.json")));

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(proxyBase("openai") + "/responses/resp_0f2a9c7e41b3d58a6c0e2f4b7d9a1c3e5"))
                .header("X-Tracker-Key", projectKey).GET().build();
        assertThat(http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);

        assertThat(OPENAI.takeRequest(5, TimeUnit.SECONDS).getPath())
                .isEqualTo("/v1/responses/resp_0f2a9c7e41b3d58a6c0e2f4b7d9a1c3e5");
        Thread.sleep(1500);
        assertThat(events()).isEmpty(); // a GET would double-count the original call
    }

    @Test
    void requestModelIsUsedWhenTheResponseHasNone() throws Exception {
        OPENAI.enqueue(jsonResponse(400,
                "{\"error\":{\"message\":\"Invalid input\",\"type\":\"invalid_request_error\"}}"
                        .getBytes(StandardCharsets.UTF_8)));

        rawPost("openai", "/responses", "{\"model\":\"gpt-4o\",\"input\":42}", "X-Tracker-Key", projectKey);

        assertThat(awaitSingleEvent()).containsEntry("status_code", 400)
                .containsEntry("endpoint", "responses")
                .containsEntry("model", "gpt-4o");
    }
}
