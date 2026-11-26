package dev.llmcosttracker.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

/**
 * Milestone 3: a custom OpenAI-compatible provider ({@code TRACKER_CUSTOM_PROVIDERS=localllm=<mock>/v1}) works
 * with the OpenAI SDK pointed at {@code /proxy/localllm}, and traffic only ever reaches the configured URL.
 */
class CustomProviderIntegrationTest extends ProxyIntegrationTestBase {

    @Test
    void openAiSdkWorksAgainstACustomProviderPricedOnItsOwn() throws Exception {
        CUSTOM.enqueue(jsonResponse(200, fixture("openai/chat-completion.json")));
        int openAiBefore = OPENAI.getRequestCount();
        int anthropicBefore = ANTHROPIC.getRequestCount();

        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey("local-NOT-A-REAL-KEY")
                .baseUrl(proxyBase(CUSTOM_NAME))
                .putHeader("X-Tracker-Key", projectKey)
                .maxRetries(0)
                .build();
        ChatCompletion completion = client.chat().completions().create(ChatCompletionCreateParams.builder()
                .model("gpt-4o")
                .addUserMessage("hi")
                .build());

        assertThat(completion.id()).isEqualTo("chatcmpl-CJrT7q2mX0f3kQzL9vYbN4wE8sHdA");
        RecordedRequest upstream = CUSTOM.takeRequest(5, TimeUnit.SECONDS);
        assertThat(upstream.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(upstream.getHeader("Authorization")).isEqualTo("Bearer local-NOT-A-REAL-KEY");
        assertThat(OPENAI.getRequestCount()).isEqualTo(openAiBefore);
        assertThat(ANTHROPIC.getRequestCount()).isEqualTo(anthropicBefore);

        Map<String, Object> row = awaitSingleEvent();
        assertThat(row).containsEntry("provider", CUSTOM_NAME)
                .containsEntry("endpoint", "chat.completions")
                .containsEntry("input_tokens", 93)
                .containsEntry("cache_read_tokens", 1024);
        // Priced with the localllm row ($0.20 / $0.40 / $0.10), not OpenAI's gpt-4o row:
        // 93*0.2 + 46*0.4 + 1024*0.1 = 18.6 + 18.4 + 102.4 = 139.4 -> 0.0001394
        assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo("0.00013940");
    }

    @Test
    void responsesEndpointIsRecordedForCustomProvidersToo() throws Exception {
        CUSTOM.enqueue(jsonResponse(200, fixture("openai/response.json")));

        rawPost(CUSTOM_NAME, "/responses", "{\"model\":\"o4-mini\"}", "X-Tracker-Key", projectKey);

        assertThat(CUSTOM.takeRequest(5, TimeUnit.SECONDS).getPath()).isEqualTo("/v1/responses");
        Map<String, Object> row = awaitSingleEvent();
        assertThat(row).containsEntry("provider", CUSTOM_NAME).containsEntry("endpoint", "responses");
        assertThat(row.get("cost_usd")).isNull(); // no localllm price for o4-mini: unpriced, not OpenAI's price
    }

    @Test
    void pathTricksStillOnlyReachTheConfiguredUpstream() throws Exception {
        int openAiBefore = OPENAI.getRequestCount();
        int anthropicBefore = ANTHROPIC.getRequestCount();

        for (String trick : new String[] {"//evil.example/v1/x", "/@evil.example/x"}) {
            CUSTOM.enqueue(jsonResponse(200, "{}".getBytes()));
            HttpRequest request = HttpRequest.newBuilder(URI.create(proxyBase(CUSTOM_NAME) + trick))
                    .header("X-Tracker-Key", projectKey).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).as(trick).isEqualTo(200);
            RecordedRequest upstream = CUSTOM.takeRequest(5, TimeUnit.SECONDS);
            assertThat(upstream).as(trick).isNotNull();
            assertThat(upstream.getPath()).as(trick).isEqualTo("/v1" + trick);
        }
        assertThat(OPENAI.getRequestCount()).isEqualTo(openAiBefore);
        assertThat(ANTHROPIC.getRequestCount()).isEqualTo(anthropicBefore);
    }

    @Test
    void unconfiguredProviderNameIs404AndNothingIsCalled() throws Exception {
        int total = OPENAI.getRequestCount() + ANTHROPIC.getRequestCount() + CUSTOM.getRequestCount();

        HttpResponse<byte[]> response = rawPost("groq", "/chat/completions", "{}", "X-Tracker-Key", projectKey);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(new String(response.body())).contains("unknown_provider", "openai, anthropic, " + CUSTOM_NAME);
        assertThat(OPENAI.getRequestCount() + ANTHROPIC.getRequestCount() + CUSTOM.getRequestCount()).isEqualTo(total);
    }
}
