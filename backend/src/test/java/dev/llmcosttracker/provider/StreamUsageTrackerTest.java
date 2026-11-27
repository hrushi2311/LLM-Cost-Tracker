package dev.llmcosttracker.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;

class StreamUsageTrackerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static byte[] fixture(String path) throws IOException {
        try (InputStream in = StreamUsageTrackerTest.class.getResourceAsStream("/fixtures/" + path)) {
            return in.readAllBytes();
        }
    }

    private static StreamUsageTracker track(Endpoint endpoint, byte[] stream) {
        StreamUsageTracker t = new StreamUsageTracker(endpoint);
        t.feed(stream);
        return t;
    }

    // --- streamed usage equals the non-streaming equivalent ------------------------------------------------------

    @Test
    void chatCompletionsStreamMatchesNonStreaming() throws IOException {
        StreamUsageTracker t = track(Endpoint.CHAT_COMPLETIONS, fixture("openai/chat-completion.sse"));

        assertThat(t.usage()).isEqualTo(OpenAiChatUsageParser.parse(JSON.readTree(fixture("openai/chat-completion.json"))));
        assertThat(t.completedNormally()).isTrue();
    }

    @Test
    void responsesStreamMatchesNonStreaming() throws IOException {
        StreamUsageTracker t = track(Endpoint.RESPONSES, fixture("openai/response.sse"));

        assertThat(t.usage()).isEqualTo(OpenAiResponsesUsageParser.parse(JSON.readTree(fixture("openai/response.json"))));
        assertThat(t.completedNormally()).isTrue();
    }

    @Test
    void anthropicStreamMatchesNonStreaming() throws IOException {
        StreamUsageTracker t = track(Endpoint.MESSAGES, fixture("anthropic/message.sse"));

        assertThat(t.usage()).isEqualTo(AnthropicMessagesUsageParser.parse(JSON.readTree(fixture("anthropic/message.json"))));
        assertThat(t.completedNormally()).isTrue();
    }

    @Test
    void chunkBoundariesAnywhereGiveTheSameResult() throws IOException {
        byte[] stream = fixture("anthropic/message.sse");
        Random random = new Random(42);
        for (int run = 0; run < 50; run++) {
            StreamUsageTracker t = new StreamUsageTracker(Endpoint.MESSAGES);
            int i = 0;
            while (i < stream.length) {
                int n = Math.min(stream.length - i, 1 + random.nextInt(40));
                byte[] chunk = new byte[n];
                System.arraycopy(stream, i, chunk, 0, n);
                t.feed(chunk);
                i += n;
            }
            assertThat(t.usage()).isEqualTo(new Usage("claude-sonnet-5-5", 2095, 503, 4102, 2051, 0));
        }
    }

    @Test
    void crlfLineEndingsWork() throws IOException {
        byte[] crlf = new String(fixture("openai/chat-completion.sse"), StandardCharsets.UTF_8)
                .replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);

        StreamUsageTracker t = track(Endpoint.CHAT_COMPLETIONS, crlf);

        assertThat(t.usage().inputTokens()).isEqualTo(93);
        assertThat(t.completedNormally()).isTrue();
    }

    // --- incomplete streams --------------------------------------------------------------------------------------

    @Test
    void chatWithoutUsageChunkIsIncompleteWithZeroUsage() {
        String stream = "data: {\"model\":\"gpt-4o\",\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\ndata: [DONE]\n\n";

        StreamUsageTracker t = track(Endpoint.CHAT_COMPLETIONS, stream.getBytes(StandardCharsets.UTF_8));

        assertThat(t.completedNormally()).isFalse();
        assertThat(t.usage()).isEqualTo(new Usage("gpt-4o", 0, 0, 0, 0, 0));
    }

    @Test
    void streamCutOffMidwayKeepsWhatWasSeen() throws IOException {
        byte[] full = fixture("anthropic/message.sse");
        String partial = new String(full, StandardCharsets.UTF_8);
        partial = partial.substring(0, partial.indexOf("event: content_block_stop"));

        StreamUsageTracker t = track(Endpoint.MESSAGES, partial.getBytes(StandardCharsets.UTF_8));

        assertThat(t.completedNormally()).isFalse();
        // input and cache from message_start; output is the initial count from message_start
        assertThat(t.usage()).isEqualTo(new Usage("claude-sonnet-5-5", 2095, 1, 4102, 2051, 0));
    }

    @Test
    void anthropicMessageDeltaCountsAreCumulativeSoTheLastWins() {
        String stream = """
                event: message_start
                data: {"type":"message_start","message":{"model":"claude-sonnet-5-5","usage":{"input_tokens":10,"output_tokens":1}}}

                event: message_delta
                data: {"type":"message_delta","usage":{"output_tokens":40}}

                event: message_delta
                data: {"type":"message_delta","usage":{"output_tokens":90,"input_tokens":12,"cache_read_input_tokens":5,"cache_creation_input_tokens":3,"output_tokens_details":{"thinking_tokens":30}}}

                event: message_stop
                data: {"type":"message_stop"}

                """;

        StreamUsageTracker t = track(Endpoint.MESSAGES, stream.getBytes(StandardCharsets.UTF_8));

        assertThat(t.usage()).isEqualTo(new Usage("claude-sonnet-5-5", 12, 90, 5, 3, 30));
        assertThat(t.completedNormally()).isTrue();
    }

    @Test
    void anthropicErrorEventMarksIncompleteAndKeepsUsageSeen() {
        String stream = """
                event: message_start
                data: {"type":"message_start","message":{"model":"claude-sonnet-5-5","usage":{"input_tokens":2095,"output_tokens":1}}}

                event: error
                data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}

                """;

        StreamUsageTracker t = track(Endpoint.MESSAGES, stream.getBytes(StandardCharsets.UTF_8));

        assertThat(t.completedNormally()).isFalse();
        assertThat(t.usage().inputTokens()).isEqualTo(2095);
    }

    @Test
    void responsesErrorAndFailedEventsMarkIncomplete() {
        String error = "event: error\ndata: {\"type\":\"error\",\"code\":\"server_error\",\"message\":\"boom\",\"param\":null,\"sequence_number\":3}\n\n";
        String failed = "event: response.failed\ndata: {\"type\":\"response.failed\",\"sequence_number\":4,"
                + "\"response\":{\"model\":\"gpt-4o\",\"status\":\"failed\",\"usage\":{\"input_tokens\":20,\"output_tokens\":2}}}\n\n";

        StreamUsageTracker e = track(Endpoint.RESPONSES, error.getBytes(StandardCharsets.UTF_8));
        StreamUsageTracker f = track(Endpoint.RESPONSES, failed.getBytes(StandardCharsets.UTF_8));

        assertThat(e.completedNormally()).isFalse();
        assertThat(f.completedNormally()).isFalse();
        assertThat(f.usage()).isEqualTo(new Usage("gpt-4o", 20, 2, 0, 0, 0));
    }

    @Test
    void chatErrorChunkMarksIncomplete() {
        String stream = "data: {\"error\":{\"message\":\"The server had an error\",\"type\":\"server_error\"}}\n\n";

        assertThat(track(Endpoint.CHAT_COMPLETIONS, stream.getBytes(StandardCharsets.UTF_8)).completedNormally()).isFalse();
    }

    // --- bounded memory ------------------------------------------------------------------------------------------

    @Test
    void hugeResponseCompletedEventIsParsedWithBoundedMemory() {
        // A 20 MB response.completed line (the Responses API repeats the whole output in it).
        String head = "event: response.completed\ndata: {\"type\":\"response.completed\",\"sequence_number\":9,"
                + "\"response\":{\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\",\"model\":\"o4-mini-2025-04-16\","
                + "\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"";
        String unit = "Lorem \\\"usage\\\": {\\\"input_tokens\\\": 999999} ipsum. ";
        String filler = unit.repeat(20 * 1024 * 1024 / unit.length() + 1);
        String tail = "\"}]}],\"usage\":{\"input_tokens\":1500,\"input_tokens_details\":{\"cached_tokens\":1024},"
                + "\"output_tokens\":900,\"output_tokens_details\":{\"reasoning_tokens\":640},\"total_tokens\":2400},"
                + "\"user\":null,\"metadata\":{}}}\n\n";
        StreamUsageTracker t = new StreamUsageTracker(Endpoint.RESPONSES);
        int maxRetained = 0;

        t.feed(head.getBytes(StandardCharsets.UTF_8));
        byte[] fillerBytes = filler.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < fillerBytes.length; i += 64 * 1024) {
            byte[] chunk = new byte[Math.min(64 * 1024, fillerBytes.length - i)];
            System.arraycopy(fillerBytes, i, chunk, 0, chunk.length);
            t.feed(chunk);
            maxRetained = Math.max(maxRetained, t.retainedBytes());
        }
        t.feed(tail.getBytes(StandardCharsets.UTF_8));

        assertThat(fillerBytes.length).isGreaterThanOrEqualTo(20 * 1024 * 1024);
        assertThat(maxRetained).isLessThanOrEqualTo(SseLineReader.HEAD + SseLineReader.TAIL);
        // Escaped "usage" text inside the output does not confuse the tail parse.
        assertThat(t.usage()).isEqualTo(new Usage("o4-mini-2025-04-16", 476, 900, 1024, 0, 640));
        assertThat(t.completedNormally()).isTrue();
    }

    @Test
    void longStreamsKeepOnlyTheCurrentLine() {
        StreamUsageTracker t = new StreamUsageTracker(Endpoint.CHAT_COMPLETIONS);
        byte[] chunk = ("data: {\"model\":\"gpt-4o\",\"choices\":[{\"delta\":{\"content\":\"token \"}}],\"usage\":null}\n\n")
                .getBytes(StandardCharsets.UTF_8);
        int maxRetained = 0;
        for (int i = 0; i < 200_000; i++) { // ~16 MB of stream
            t.feed(chunk);
            maxRetained = Math.max(maxRetained, t.retainedBytes());
        }
        t.feed("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":200000}}\n\ndata: [DONE]\n\n"
                .getBytes(StandardCharsets.UTF_8));

        assertThat(maxRetained).isZero(); // each chunk ends with a newline, so nothing is held between chunks
        assertThat(t.usage()).isEqualTo(new Usage("gpt-4o", 10, 200_000, 0, 0, 0));
        assertThat(t.completedNormally()).isTrue();
    }

    @Test
    void lastObjectAfterKeyIgnoresBracesInStrings() {
        assertThat(StreamUsageTracker.lastObjectAfterKey(
                "xx\"usage\":{\"a\":\"}{\\\"\",\"b\":{\"c\":1}},\"user\":null", "\"usage\":").toString())
                .isEqualTo("{\"a\":\"}{\\\"\",\"b\":{\"c\":1}}");
    }
}
