package dev.llmcosttracker.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;

/** Milestone 5: SSE streaming through the proxy. Costs below are the same hand-computed values as non-streaming. */
class StreamingIntegrationTest extends ProxyIntegrationTestBase {

    private static MockResponse sse(byte[] body) {
        return new MockResponse().setHeader("Content-Type", "text/event-stream; charset=utf-8")
                .setBody(new okio.Buffer().write(body));
    }

    private static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    // --- bytes identical, usage equal to non-streaming, for all three endpoints ----------------------------------

    @Test
    void chatCompletionsStreamIsByteIdenticalAndPricedLikeNonStreaming() throws Exception {
        byte[] stream = fixture("openai/chat-completion.sse");
        OPENAI.enqueue(sse(stream));

        HttpResponse<byte[]> response = rawPost("openai", "/chat/completions",
                "{\"model\":\"gpt-4o\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                "X-Tracker-Key", projectKey);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(stream);
        Map<String, Object> row = awaitSingleEvent();
        assertThat(row).containsEntry("endpoint", "chat.completions").containsEntry("streamed", true)
                .containsEntry("incomplete", false).containsEntry("input_tokens", 93)
                .containsEntry("cache_read_tokens", 1024).containsEntry("output_tokens", 46);
        assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo("0.00106600");
    }

    @Test
    void responsesStreamIsByteIdenticalAndPricedLikeNonStreaming() throws Exception {
        byte[] stream = fixture("openai/response.sse");
        OPENAI.enqueue(sse(stream));

        HttpResponse<byte[]> response = rawPost("openai", "/responses",
                "{\"model\":\"o4-mini\",\"stream\":true,\"input\":\"plan\"}", "X-Tracker-Key", projectKey);

        assertThat(response.body()).isEqualTo(stream);
        assertThat(utf8(OPENAI.takeRequest(5, TimeUnit.SECONDS).getBody().readByteArray()))
                .isEqualTo("{\"model\":\"o4-mini\",\"stream\":true,\"input\":\"plan\"}"); // Responses: never modified
        Map<String, Object> row = awaitSingleEvent();
        assertThat(row).containsEntry("endpoint", "responses").containsEntry("streamed", true)
                .containsEntry("incomplete", false).containsEntry("input_tokens", 476)
                .containsEntry("output_tokens", 900).containsEntry("reasoning_tokens", 640);
        assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo("0.00510000");
    }

    @Test
    void anthropicStreamIsByteIdenticalAndPricedLikeNonStreaming() throws Exception {
        byte[] stream = fixture("anthropic/message.sse");
        ANTHROPIC.enqueue(sse(stream));

        HttpResponse<byte[]> response = rawPost("anthropic", "/v1/messages",
                "{\"model\":\"claude-sonnet-5-5\",\"max_tokens\":64,\"stream\":true,\"messages\":[]}",
                "X-Tracker-Key", projectKey);

        assertThat(response.body()).isEqualTo(stream);
        Map<String, Object> row = awaitSingleEvent();
        assertThat(row).containsEntry("endpoint", "messages").containsEntry("streamed", true)
                .containsEntry("incomplete", false).containsEntry("input_tokens", 2095)
                .containsEntry("output_tokens", 503).containsEntry("cache_read_tokens", 4102)
                .containsEntry("cache_write_tokens", 2051);
        assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo("0.03033580");
    }

    // --- official SDKs in streaming mode -------------------------------------------------------------------------

    @Test
    void officialOpenAiSdkStreamsThroughTheProxy() throws Exception {
        OPENAI.enqueue(sse(fixture("openai/chat-completion.sse")));
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey("sk-test-NOT-A-REAL-KEY").baseUrl(proxyBase("openai"))
                .putHeader("X-Tracker-Key", projectKey).maxRetries(0).build();
        StringBuilder text = new StringBuilder();
        int chunks;

        try (com.openai.core.http.StreamResponse<ChatCompletionChunk> stream = client.chat().completions()
                .createStreaming(ChatCompletionCreateParams.builder().model(ChatModel.GPT_4O).addUserMessage("hi").build())) {
            AtomicInteger count = new AtomicInteger();
            stream.stream().forEach(chunk -> {
                count.incrementAndGet();
                chunk.choices().forEach(c -> c.delta().content().ifPresent(text::append));
            });
            chunks = count.get();
        }

        assertThat(text.toString()).isEqualTo("Hello! How can I help you today?");
        assertThat(chunks).isEqualTo(5); // includes the extra final usage chunk with empty choices
        assertThat(utf8(OPENAI.takeRequest(5, TimeUnit.SECONDS).getBody().readByteArray()))
                .contains("\"stream_options\":{\"include_usage\":true}");
        Map<String, Object> row = awaitSingleEvent();
        assertThat(row).containsEntry("streamed", true).containsEntry("incomplete", false);
        assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo("0.00106600");
    }

    @Test
    void officialAnthropicSdkStreamsThroughTheProxy() throws Exception {
        ANTHROPIC.enqueue(sse(fixture("anthropic/message.sse")));
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey("sk-ant-test-NOT-A-REAL-KEY").baseUrl(proxyBase("anthropic"))
                .putHeader("X-Tracker-Key", projectKey).maxRetries(0).build();
        StringBuilder text = new StringBuilder();
        AtomicInteger events = new AtomicInteger();

        try (com.anthropic.core.http.StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(
                MessageCreateParams.builder().model("claude-sonnet-5-5").maxTokens(64).addUserMessage("hi").build())) {
            stream.stream().forEach(event -> {
                events.incrementAndGet();
                event.contentBlockDelta().flatMap(d -> d.delta().text()).ifPresent(t -> text.append(t.text()));
            });
        }

        assertThat(text.toString()).isEqualTo("Hi! My name is Claude.");
        assertThat(events.get()).isGreaterThanOrEqualTo(7);
        Map<String, Object> row = awaitSingleEvent();
        assertThat(row).containsEntry("streamed", true).containsEntry("incomplete", false)
                .containsEntry("input_tokens", 2095).containsEntry("output_tokens", 503);
        assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo("0.03033580");
    }

    // --- nothing is buffered ---------------------------------------------------------------------------------------

    @Test
    void firstChunkReachesTheClientLongBeforeTheStreamEnds() throws Exception {
        byte[] stream = fixture("openai/chat-completion.sse");
        // MockWebServer sends 200 bytes every 400 ms, so the ~1.9 KB stream takes about 4 seconds.
        OPENAI.enqueue(sse(stream).throttleBody(200, 400, TimeUnit.MILLISECONDS));

        long start = System.nanoTime();
        HttpRequest request = HttpRequest.newBuilder(URI.create(proxyBase("openai") + "/chat/completions"))
                .header("X-Tracker-Key", projectKey).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o\",\"stream\":true}")).build();
        long firstChunkMs = -1;
        try (InputStream body = http.send(request, HttpResponse.BodyHandlers.ofInputStream()).body();
                BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (firstChunkMs < 0 && line.startsWith("data:")) {
                    firstChunkMs = (System.nanoTime() - start) / 1_000_000;
                }
            }
        }
        long totalMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(totalMs).isGreaterThan(2_000);
        assertThat(firstChunkMs).isBetween(0L, 1_200L).isLessThan(totalMs / 2);
        assertThat(awaitSingleEvent()).containsEntry("incomplete", false);
    }

    // --- client disconnects ----------------------------------------------------------------------------------------

    /** Sends a request on a raw socket, reads until a line contains {@code until}, then resets the connection. */
    private void postAndDisconnect(String path, String body, String until) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(15_000);
            socket.setSoLinger(true, 0); // close with RST, like a client that goes away
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            OutputStream out = socket.getOutputStream();
            out.write(("POST " + path + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                    + "X-Tracker-Key: " + projectKey + "\r\nContent-Length: " + bytes.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null && !line.contains(until)) {
                // keep reading
            }
        }
    }

    @Test
    void clientDisconnectCancelsTheUpstreamRequestAndRecordsAnIncompleteEvent() throws Exception {
        CountDownLatch upstreamSawDisconnect = new CountDownLatch(1);
        AtomicInteger chunksSent = new AtomicInteger();
        slowHandler = exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                for (int i = 0; i < 300; i++) { // 30 s if nobody stops it
                    os.write(("data: {\"model\":\"slow-model\",\"choices\":[{\"delta\":{\"content\":\"t" + i
                            + " \"}}],\"usage\":null}\n\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    chunksSent.incrementAndGet();
                    Thread.sleep(100);
                }
            } catch (IOException e) {
                upstreamSawDisconnect.countDown(); // the proxy closed the upstream connection
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        postAndDisconnect("/proxy/" + SLOW_NAME + "/chat/completions", "{\"model\":\"slow-model\",\"stream\":true}", "t2 ");

        assertThat(upstreamSawDisconnect.await(10, TimeUnit.SECONDS)).as("upstream request cancelled").isTrue();
        assertThat(chunksSent.get()).isLessThan(100);
        Map<String, Object> row = awaitSingleEvent();
        assertThat(row).containsEntry("provider", SLOW_NAME).containsEntry("model", "slow-model")
                .containsEntry("streamed", true).containsEntry("incomplete", true).containsEntry("status_code", 200);
    }

    @Test
    void disconnectMidStreamKeepsTheUsageSeenSoFar() throws Exception {
        ANTHROPIC.enqueue(sse(fixture("anthropic/message.sse")).throttleBody(600, 1, TimeUnit.SECONDS));

        postAndDisconnect("/proxy/anthropic/v1/messages", "{\"model\":\"claude-sonnet-5-5\",\"stream\":true}",
                "\"message_start\"");

        Map<String, Object> row = awaitSingleEvent();
        assertThat(row).containsEntry("streamed", true).containsEntry("incomplete", true)
                .containsEntry("input_tokens", 2095).containsEntry("cache_read_tokens", 4102)
                .containsEntry("cache_write_tokens", 2051).containsEntry("output_tokens", 1);
    }

    // --- upstream errors during a stream ---------------------------------------------------------------------------

    @Test
    void errorEventIsPassedThroughAndRecordedAsIncomplete() throws Exception {
        String full = utf8(fixture("anthropic/message.sse"));
        byte[] stream = (full.substring(0, full.indexOf("event: content_block_start"))
                + "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n\n")
                .getBytes(StandardCharsets.UTF_8);
        ANTHROPIC.enqueue(sse(stream));

        HttpResponse<byte[]> response = rawPost("anthropic", "/v1/messages",
                "{\"model\":\"claude-sonnet-5-5\",\"stream\":true}", "X-Tracker-Key", projectKey);

        assertThat(response.body()).isEqualTo(stream);
        assertThat(awaitSingleEvent()).containsEntry("incomplete", true).containsEntry("streamed", true)
                .containsEntry("input_tokens", 2095);
    }

    @Test
    void upstreamDroppingTheConnectionIsPassedOnAndRecorded() throws Exception {
        ANTHROPIC.enqueue(sse(fixture("anthropic/message.sse"))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));

        assertThatThrownBy(() -> rawPost("anthropic", "/v1/messages",
                "{\"model\":\"claude-sonnet-5-5\",\"stream\":true}", "X-Tracker-Key", projectKey))
                .isInstanceOf(IOException.class); // the client sees the stream break, as it would without the proxy

        assertThat(awaitSingleEvent()).containsEntry("incomplete", true).containsEntry("streamed", true)
                .containsEntry("input_tokens", 2095);
    }

    // --- stream_options.include_usage ------------------------------------------------------------------------------

    @Test
    void includeUsageIsAddedOnlyWhenStreamOptionsIsAbsentChangingNothingElse() throws Exception {
        OPENAI.enqueue(sse(fixture("openai/chat-completion.sse")));
        String body = "{ \"model\": \"gpt-4o\", \"stream\": true,\n \"messages\": [{\"role\":\"user\",\"content\":\"hi\"}] }";

        rawPost("openai", "/chat/completions", body, "X-Tracker-Key", projectKey);

        RecordedRequest upstream = OPENAI.takeRequest(5, TimeUnit.SECONDS);
        assertThat(utf8(upstream.getBody().readByteArray())).isEqualTo(
                "{ \"model\": \"gpt-4o\", \"stream\": true,\n \"messages\": [{\"role\":\"user\",\"content\":\"hi\"}] "
                        + ",\"stream_options\":{\"include_usage\":true}}");
        awaitSingleEvent();
    }

    @Test
    void explicitIncludeUsageFalseIsLeftAloneAndRecordedIncompleteWithoutUsage() throws Exception {
        String full = utf8(fixture("openai/chat-completion.sse"));
        // Without include_usage the upstream sends no usage chunk.
        String withoutUsage = full.replaceAll("data: \\{[^\\n]*\"choices\":\\[\\],\"usage\":\\{[^\\n]*\\n\\n", "");
        assertThat(withoutUsage).doesNotContain("\"prompt_tokens\"").contains("[DONE]");
        OPENAI.enqueue(sse(withoutUsage.getBytes(StandardCharsets.UTF_8)));
        String body = "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":false},\"messages\":[]}";

        HttpResponse<byte[]> response = rawPost("openai", "/chat/completions", body, "X-Tracker-Key", projectKey);

        assertThat(utf8(response.body())).isEqualTo(withoutUsage);
        assertThat(utf8(OPENAI.takeRequest(5, TimeUnit.SECONDS).getBody().readByteArray())).isEqualTo(body);
        Map<String, Object> row = awaitSingleEvent();
        assertThat(row).containsEntry("streamed", true).containsEntry("incomplete", true)
                .containsEntry("model", "gpt-4o-2024-08-06").containsEntry("input_tokens", 0)
                .containsEntry("output_tokens", 0);
    }
}
