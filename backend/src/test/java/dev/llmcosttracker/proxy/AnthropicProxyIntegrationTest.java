package dev.llmcosttracker.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

/** Milestone 3: Anthropic Messages through the proxy, with the official Anthropic Java SDK. */
class AnthropicProxyIntegrationTest extends ProxyIntegrationTestBase {

    static final String ANTHROPIC_KEY = "sk-ant-test-NOT-A-REAL-KEY-91b2";

    @Test
    void officialSdkWorksThroughTheProxyAndCacheTokensArePriced() throws Exception {
        byte[] body = fixture("anthropic/message.json");
        ANTHROPIC.enqueue(jsonResponse(200, body));

        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(ANTHROPIC_KEY)
                .baseUrl(proxyBase("anthropic"))
                .putHeader("X-Tracker-Key", projectKey)
                .putHeader("anthropic-beta", "test-beta-2026-01-01")
                .maxRetries(0)
                .build();
        Message message = client.messages().create(MessageCreateParams.builder()
                .model("claude-sonnet-5-5")
                .maxTokens(1024)
                .addUserMessage("Hello, Claude")
                .build());

        assertThat(message.id()).isEqualTo("msg_01HcQ7vXbN2mK8pR4sT6wY9zA3");
        assertThat(message.usage().inputTokens()).isEqualTo(2095);
        assertThat(message.usage().cacheReadInputTokens()).contains(4102L);

        RecordedRequest upstream = ANTHROPIC.takeRequest(5, TimeUnit.SECONDS);
        assertThat(upstream.getPath()).isEqualTo("/v1/messages");
        // The SDK sends its key as x-api-key or Authorization: Bearer depending on version; either way it arrives
        // unchanged. (x-api-key pass-through is asserted explicitly in the raw-HTTP test below.)
        String credential = upstream.getHeader("x-api-key") != null
                ? upstream.getHeader("x-api-key")
                : upstream.getHeader("Authorization");
        assertThat(credential).isIn(ANTHROPIC_KEY, "Bearer " + ANTHROPIC_KEY);
        assertThat(upstream.getHeader("anthropic-version")).isNotBlank(); // whatever the SDK sent, passed through
        assertThat(upstream.getHeader("anthropic-beta")).isEqualTo("test-beta-2026-01-01");
        assertThat(upstream.getHeaders().names()).noneMatch(n -> n.toLowerCase().startsWith("x-tracker-"));

        Map<String, Object> row = awaitSingleEvent();
        assertThat(row).containsEntry("provider", "anthropic")
                .containsEntry("endpoint", "messages")
                .containsEntry("model", "claude-sonnet-5-5")
                .containsEntry("input_tokens", 2095)        // stored as reported: already excludes cache
                .containsEntry("output_tokens", 503)
                .containsEntry("cache_read_tokens", 4102)
                .containsEntry("cache_write_tokens", 2051)
                .containsEntry("reasoning_tokens", 0)
                .containsEntry("status_code", 200);
        // $4 in / $20 out / $0.40 cache read / $5 cache write per Mtok:
        // 2095*4 + 503*20 + 4102*0.4 + 2051*5 = 8380 + 10060 + 1640.8 + 10255 = 30335.8 -> 0.0303358
        assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo("0.03033580");
    }

    @Test
    void anthropicHeadersAndBytesPassThroughUnchanged() throws Exception {
        byte[] body = fixture("anthropic/message.json");
        ANTHROPIC.enqueue(jsonResponse(200, body).setHeader("request-id", "req_011CAnthropicMock")
                .setHeader("anthropic-ratelimit-tokens-remaining", "79000"));

        HttpResponse<byte[]> response = rawPost("anthropic", "/v1/messages",
                "{\"model\":\"claude-sonnet-5-5\",\"max_tokens\":64,\"messages\":[]}",
                "x-api-key", ANTHROPIC_KEY,
                "anthropic-version", "2026-10-21",
                "anthropic-beta", "inline-tools-2026-09-15,thinking-binding-controls-2026-08-01",
                "X-Tracker-Key", projectKey);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(body);
        assertThat(response.headers().firstValue("request-id")).contains("req_011CAnthropicMock");
        assertThat(response.headers().firstValue("anthropic-ratelimit-tokens-remaining")).contains("79000");

        RecordedRequest upstream = ANTHROPIC.takeRequest(5, TimeUnit.SECONDS);
        assertThat(upstream.getHeader("anthropic-version")).isEqualTo("2026-10-21");
        assertThat(upstream.getHeader("anthropic-beta"))
                .isEqualTo("inline-tools-2026-09-15,thinking-binding-controls-2026-08-01");
        assertThat(upstream.getHeader("x-api-key")).isEqualTo(ANTHROPIC_KEY);
        awaitSingleEvent();
    }

    @Test
    void overloadedErrorIsRelayedAndRecordedWithRequestModel() throws Exception {
        byte[] error = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"
                .getBytes(StandardCharsets.UTF_8);
        ANTHROPIC.enqueue(jsonResponse(529, error));

        HttpResponse<byte[]> response = rawPost("anthropic", "/v1/messages",
                "{\"model\":\"claude-sonnet-5-5\",\"max_tokens\":64}", "X-Tracker-Key", projectKey);

        assertThat(response.statusCode()).isEqualTo(529);
        assertThat(response.body()).isEqualTo(error);
        assertThat(awaitSingleEvent()).containsEntry("status_code", 529)
                .containsEntry("model", "claude-sonnet-5-5")
                .containsEntry("input_tokens", 0);
    }

    @Test
    void otherAnthropicPathsAreForwardedButNotRecorded() throws Exception {
        ANTHROPIC.enqueue(jsonResponse(200, "{\"input_tokens\":42}".getBytes(StandardCharsets.UTF_8)));

        HttpResponse<byte[]> response = rawPost("anthropic", "/v1/messages/count_tokens",
                "{\"model\":\"claude-sonnet-5-5\",\"messages\":[]}", "X-Tracker-Key", projectKey);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(ANTHROPIC.takeRequest(5, TimeUnit.SECONDS).getPath()).isEqualTo("/v1/messages/count_tokens");
        Thread.sleep(1500);
        assertThat(events()).isEmpty();
    }

    // Streaming Anthropic calls are covered by StreamingIntegrationTest (milestone 5).
}
