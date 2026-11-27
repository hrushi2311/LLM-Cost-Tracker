package dev.llmcosttracker.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Milestone 2 acceptance: the official OpenAI Java SDK, pointed at the proxy (which points at a MockWebServer
 * playing OpenAI), works unchanged; response bytes are identical; a priced usage_event row is written; secrets are
 * never forwarded, stored or logged.
 */
@ExtendWith(OutputCaptureExtension.class)
class OpenAiProxyIntegrationTest extends ProxyIntegrationTestBase {

    static final String PROVIDER_KEY = "sk-test-NOT-A-REAL-KEY-7f3a9c2e";

    HttpResponse<byte[]> postOpenAi(String path, String body, String... headers) throws Exception {
        return rawPost("openai", path, body, headers);
    }

    @Test
    void officialSdkWorksThroughTheProxyAndAPricedRowIsWritten() throws Exception {
        OPENAI.enqueue(jsonResponse(200, fixture("openai/chat-completion.json")).setHeader("openai-processing-ms", "321"));

        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(PROVIDER_KEY)
                .baseUrl(proxyBase("openai"))
                .putHeader("X-Tracker-Key", projectKey)
                .maxRetries(0)
                .build();
        ChatCompletion completion = client.chat().completions().create(ChatCompletionCreateParams.builder()
                .model(ChatModel.GPT_4O)
                .addUserMessage("Say hello")
                .build());

        assertThat(completion.id()).isEqualTo("chatcmpl-CJrT7q2mX0f3kQzL9vYbN4wE8sHdA");
        assertThat(completion.choices().get(0).message().content()).contains("Hello! How can I help you today?");
        assertThat(completion.usage().orElseThrow().promptTokens()).isEqualTo(1117);

        RecordedRequest upstream = OPENAI.takeRequest(5, TimeUnit.SECONDS);
        assertThat(upstream.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(upstream.getMethod()).isEqualTo("POST");
        assertThat(upstream.getHeader("Authorization")).isEqualTo("Bearer " + PROVIDER_KEY);

        Map<String, Object> row = awaitSingleEvent();
        assertThat(row).containsEntry("provider", "openai")
                .containsEntry("endpoint", "chat.completions")
                .containsEntry("model", "gpt-4o-2024-08-06") // response model wins over request "gpt-4o"
                .containsEntry("input_tokens", 93)            // 1117 prompt - 1024 cached
                .containsEntry("output_tokens", 46)
                .containsEntry("cache_read_tokens", 1024)
                .containsEntry("cache_write_tokens", 0)
                .containsEntry("reasoning_tokens", 0)
                .containsEntry("status_code", 200)
                .containsEntry("streamed", false)
                .containsEntry("incomplete", false);
        // No header, path segment or system prompt: the call belongs to the "unattributed" agent.
        assertThat(jdbc.queryForObject("SELECT name || '/' || source FROM agent WHERE id = ?", String.class,
                row.get("agent_id"))).isEqualTo("unattributed/unattributed");
        // gpt-4o test price (2024 row, matched by prefix): (93*2 + 46*8 + 1024*0.5) / 1e6
        assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo("0.00106600");
        assertThat((Integer) row.get("latency_ms")).isGreaterThanOrEqualTo(0);
    }

    @Test
    void responseBytesAndHeadersAreExactlyWhatUpstreamSent() throws Exception {
        byte[] body = fixture("openai/chat-completion.json");
        OPENAI.enqueue(jsonResponse(200, body).setHeader("openai-processing-ms", "321"));

        HttpResponse<byte[]> response = postOpenAi("/chat/completions", "{\"model\":\"gpt-4o\",\"messages\":[]}",
                "Authorization", "Bearer " + PROVIDER_KEY, "X-Tracker-Key", projectKey);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(body); // pretty-printed fixture: any re-serialisation would differ
        assertThat(response.headers().firstValue("x-request-id")).contains("req_mock_0001");
        assertThat(response.headers().firstValue("openai-processing-ms")).contains("321");
        assertThat(response.headers().firstValue("content-type")).contains("application/json");
        awaitSingleEvent();
    }

    @Test
    void requestBodyIsForwardedUnchanged() throws Exception {
        OPENAI.enqueue(jsonResponse(200, fixture("openai/chat-completion.json")));
        String requestBody = "{ \"model\" : \"gpt-4o\",\n  \"messages\": [{\"role\":\"user\",\"content\":\"hi\"}] }";

        postOpenAi("/chat/completions", requestBody, "X-Tracker-Key", projectKey);

        RecordedRequest upstream = OPENAI.takeRequest(5, TimeUnit.SECONDS);
        assertThat(upstream.getBody().readString(StandardCharsets.UTF_8)).isEqualTo(requestBody);
        awaitSingleEvent();
    }

    @Test
    void reasoningTokensAreStoredButCostedOnlyOnceInsideOutput() throws Exception {
        OPENAI.enqueue(jsonResponse(200, fixture("openai/chat-completion-reasoning.json")));

        postOpenAi("/chat/completions", "{\"model\":\"o4-mini\"}", "X-Tracker-Key", projectKey);

        Map<String, Object> row = awaitSingleEvent();
        assertThat(row).containsEntry("input_tokens", 512)
                .containsEntry("output_tokens", 1200)
                .containsEntry("reasoning_tokens", 1024);
        // o4-mini test price: (512*1 + 1200*4) / 1e6; reasoning not added again.
        assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo("0.00531200");
    }

    @Test
    void unpricedModelIsStoredWithNullCost() throws Exception {
        OPENAI.enqueue(jsonResponse(200,
                "{\"model\":\"brand-new-model\",\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":7}}"
                        .getBytes(StandardCharsets.UTF_8)));

        postOpenAi("/chat/completions", "{\"model\":\"brand-new-model\"}", "X-Tracker-Key", projectKey);

        Map<String, Object> row = awaitSingleEvent();
        assertThat(row).containsEntry("model", "brand-new-model").containsEntry("input_tokens", 5);
        assertThat(row.get("cost_usd")).isNull();
    }

    @Test
    void secretsAndTrackerHeadersAreNeverForwardedStoredOrLogged(CapturedOutput output) throws Exception {
        OPENAI.enqueue(jsonResponse(200, fixture("openai/chat-completion.json")));
        String secretRunId = "run-SECRET-4c1d";

        postOpenAi("/chat/completions", "{\"model\":\"gpt-4o\"}",
                "Authorization", "Bearer " + PROVIDER_KEY,
                "x-api-key", PROVIDER_KEY,
                "X-Tracker-Key", projectKey,
                "X-Tracker-Run-Id", secretRunId,
                "X-Tracker-Tag-Team", "search");

        // Forwarded: provider credentials untouched. Not forwarded: every X-Tracker-* header.
        RecordedRequest upstream = OPENAI.takeRequest(5, TimeUnit.SECONDS);
        assertThat(upstream.getHeader("Authorization")).isEqualTo("Bearer " + PROVIDER_KEY);
        assertThat(upstream.getHeader("x-api-key")).isEqualTo(PROVIDER_KEY);
        assertThat(upstream.getHeaders().names())
                .noneMatch(n -> n.toLowerCase().startsWith("x-tracker-"));

        // Not stored: dump every table as text and search it.
        awaitSingleEvent();
        String everything = String.join("\n", jdbc.queryForList(
                "SELECT to_jsonb(p)::text FROM project p"
                        + " UNION ALL SELECT to_jsonb(e)::text FROM usage_event e"
                        + " UNION ALL SELECT to_jsonb(a)::text FROM agent a"
                        + " UNION ALL SELECT to_jsonb(m)::text FROM model_price m", String.class));
        assertThat(everything).doesNotContain(PROVIDER_KEY, projectKey);

        // Not logged, even with web and Netty loggers at DEBUG.
        assertThat(output.getAll()).doesNotContain(PROVIDER_KEY, projectKey);

        // And the redacting log filter is installed: a careless log line is masked before it is written.
        LoggerFactory.getLogger("redaction-probe").info("Authorization: Bearer {} key={}", PROVIDER_KEY, projectKey);
        assertThat(output.getAll()).doesNotContain(PROVIDER_KEY, projectKey).contains("Authorization: Bearer [REDACTED]");
    }

    @Test
    void agentPathSegmentIsStrippedBeforeForwarding() throws Exception {
        OPENAI.enqueue(jsonResponse(200, fixture("openai/chat-completion.json")));

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(proxyBase("openai") + "/agent/planner/chat/completions"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o\"}"))
                .header("X-Tracker-Key", projectKey)
                .build();
        assertThat(http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);

        assertThat(OPENAI.takeRequest(5, TimeUnit.SECONDS).getPath()).isEqualTo("/v1/chat/completions");
        awaitSingleEvent();
    }

    @Test
    void upstreamErrorsArePassedThroughAndRecorded() throws Exception {
        byte[] error = "{\"error\":{\"message\":\"Rate limit reached\",\"type\":\"requests\",\"code\":\"rate_limit_exceeded\"}}"
                .getBytes(StandardCharsets.UTF_8);
        OPENAI.enqueue(jsonResponse(429, error).setHeader("retry-after", "7"));

        HttpResponse<byte[]> response = postOpenAi("/chat/completions", "{\"model\":\"gpt-4o\"}",
                "X-Tracker-Key", projectKey);

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.body()).isEqualTo(error);
        assertThat(response.headers().firstValue("retry-after")).contains("7");
        Map<String, Object> row = awaitSingleEvent();
        assertThat(row).containsEntry("status_code", 429)
                .containsEntry("model", "gpt-4o") // falls back to the request's model
                .containsEntry("input_tokens", 0)
                .containsEntry("output_tokens", 0);
    }

    @Test
    void nonJsonUpstreamErrorIsRelayedAndRecordedWithZeroUsage() throws Exception {
        OPENAI.enqueue(new MockResponse().setResponseCode(502)
                .setHeader("Content-Type", "text/html").setBody("<html>bad gateway</html>"));

        HttpResponse<byte[]> response = postOpenAi("/chat/completions", "{\"model\":\"gpt-4o\"}",
                "X-Tracker-Key", projectKey);

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("<html>bad gateway</html>");
        assertThat(awaitSingleEvent()).containsEntry("status_code", 502).containsEntry("input_tokens", 0);
    }

    @Test
    void otherPathsAreForwardedButNotRecorded() throws Exception {
        OPENAI.enqueue(jsonResponse(200, "{\"object\":\"list\",\"data\":[]}".getBytes(StandardCharsets.UTF_8)));

        HttpRequest request = HttpRequest.newBuilder(URI.create(proxyBase("openai") + "/models?limit=2"))
                .header("X-Tracker-Key", projectKey)
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"object\":\"list\",\"data\":[]}");
        RecordedRequest upstream = OPENAI.takeRequest(5, TimeUnit.SECONDS);
        assertThat(upstream.getPath()).isEqualTo("/v1/models?limit=2");
        assertThat(upstream.getMethod()).isEqualTo("GET");
        Thread.sleep(1500); // longer than one batch interval
        assertThat(events()).isEmpty();
    }

    @Test
    void missingOrUnknownProjectKeyIsRejectedBeforeForwarding() throws Exception {
        int before = OPENAI.getRequestCount();

        HttpResponse<byte[]> missing = postOpenAi("/chat/completions", "{}");
        HttpResponse<byte[]> unknown = postOpenAi("/chat/completions", "{}", "X-Tracker-Key", "trk_doesNotExist123");

        for (HttpResponse<byte[]> r : List.of(missing, unknown)) {
            assertThat(r.statusCode()).isEqualTo(401);
            assertThat(new String(r.body(), StandardCharsets.UTF_8))
                    .isEqualTo("{\"error\":{\"code\":\"invalid_project_key\",\"message\":"
                            + "\"Missing or unknown project key (X-Tracker-Key header or /proxy/k/{key}/ path)\"}}");
        }
        assertThat(OPENAI.getRequestCount()).isEqualTo(before);
    }

    @Test
    void unknownProviderIs404() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/proxy/nope/v1/x"))
                .header("X-Tracker-Key", projectKey).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":\"unknown_provider\"");
    }
}
