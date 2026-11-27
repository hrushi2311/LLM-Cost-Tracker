package dev.llmcosttracker.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Follows a streamed (SSE) response and keeps only what is needed for usage. It is fed a copy of each chunk as it
 * passes to the client, and holds a bounded line window ({@link SseLineReader}) plus a few counters, never the
 * stream.
 *
 * <ul>
 *   <li><b>Chat Completions:</b> the final chunk with a non-null {@code usage} (sent only with
 *       {@code stream_options.include_usage}), then {@code data: [DONE]}.
 *   <li><b>Responses:</b> {@code response.completed} carries {@code response.usage}; {@code response.failed},
 *       {@code response.incomplete} and {@code error} end the stream abnormally.
 *   <li><b>Anthropic Messages:</b> input and cache tokens from {@code message_start}; output from the last
 *       {@code message_delta}, whose counts are cumulative (so the last value wins, including any input or cache
 *       fields it repeats); {@code message_stop} ends normally, {@code error} abnormally.
 * </ul>
 */
public final class StreamUsageTracker {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TYPE = Pattern.compile("\"type\"\\s*:\\s*\"([^\"\\\\]{1,100})\"");
    private static final Pattern MODEL = Pattern.compile("\"model\"\\s*:\\s*\"([^\"\\\\]{1,200})\"");

    private final Endpoint endpoint;
    private final SseLineReader reader = new SseLineReader(this::onLine);

    private String model;
    private Usage usage;
    private boolean doneSeen;
    private boolean completed;
    private boolean errorSeen;

    // Anthropic counters (cumulative; last value wins)
    private int input;
    private int output;
    private int cacheRead;
    private int cacheWrite;
    private int thinking;
    private boolean started;

    public StreamUsageTracker(Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    public void feed(ByteBuffer bytes) {
        reader.feed(bytes);
    }

    public void feed(byte[] bytes) {
        reader.feed(bytes);
    }

    int retainedBytes() {
        return reader.retainedBytes();
    }

    /** Usage seen so far (zeros if none), with the model the stream reported. */
    public Usage usage() {
        if (endpoint == Endpoint.MESSAGES) {
            return started ? new Usage(model, input, output, cacheRead, cacheWrite, thinking) : new Usage(model, 0, 0, 0, 0, 0);
        }
        if (usage == null) {
            return new Usage(model, 0, 0, 0, 0, 0);
        }
        return usage.model() != null ? usage : new Usage(model, usage.inputTokens(), usage.outputTokens(),
                usage.cacheReadTokens(), usage.cacheWriteTokens(), usage.reasoningTokens());
    }

    /** True when the stream ended the way a successful one does, with final usage, and no error event. */
    public boolean completedNormally() {
        if (errorSeen) {
            return false;
        }
        return switch (endpoint) {
            case CHAT_COMPLETIONS -> doneSeen && usage != null;
            case RESPONSES, MESSAGES -> completed;
        };
    }

    private void onLine(SseLineReader.Line line) {
        String start = line.truncated() ? line.head() : line.text();
        if (!start.startsWith("data:")) {
            return; // event:, id:, retry:, comments and blank lines carry nothing we need
        }
        JsonNode event;
        if (line.truncated()) {
            event = fromTruncated(line.head(), line.tail());
        } else {
            String payload = start.substring(start.startsWith("data: ") ? 6 : 5).trim();
            if (payload.equals("[DONE]")) {
                doneSeen = true;
                return;
            }
            event = parse(payload);
        }
        if (event == null || !event.isObject()) {
            return;
        }
        switch (endpoint) {
            case CHAT_COMPLETIONS -> onChat(event);
            case RESPONSES -> onResponses(event);
            case MESSAGES -> onAnthropic(event);
        }
    }

    private void onChat(JsonNode chunk) {
        if (chunk.path("error").isObject()) {
            errorSeen = true;
        }
        rememberModel(chunk.path("model"));
        if (chunk.path("usage").isObject()) {
            usage = OpenAiChatUsageParser.parse(chunk);
        }
    }

    private void onResponses(JsonNode event) {
        String type = event.path("type").asText("");
        JsonNode response = event.path("response");
        rememberModel(response.path("model"));
        switch (type) {
            case "response.completed" -> {
                completed = true;
                usage = OpenAiResponsesUsageParser.parse(response);
            }
            case "response.failed", "response.incomplete" -> {
                errorSeen = true;
                if (response.path("usage").isObject()) {
                    usage = OpenAiResponsesUsageParser.parse(response);
                }
            }
            case "error" -> errorSeen = true;
            default -> { }
        }
    }

    private void onAnthropic(JsonNode event) {
        switch (event.path("type").asText("")) {
            case "message_start" -> {
                JsonNode message = event.path("message");
                rememberModel(message.path("model"));
                Usage u = AnthropicMessagesUsageParser.parse(message);
                input = u.inputTokens();
                output = u.outputTokens();
                cacheRead = u.cacheReadTokens();
                cacheWrite = u.cacheWriteTokens();
                thinking = u.reasoningTokens();
                started = true;
            }
            case "message_delta" -> {
                JsonNode u = event.path("usage");
                started = true;
                output = replaceIfPresent(u.path("output_tokens"), output);
                input = replaceIfPresent(u.path("input_tokens"), input);
                cacheRead = replaceIfPresent(u.path("cache_read_input_tokens"), cacheRead);
                cacheWrite = replaceIfPresent(u.path("cache_creation_input_tokens"), cacheWrite);
                thinking = replaceIfPresent(u.path("output_tokens_details").path("thinking_tokens"), thinking);
            }
            case "message_stop" -> completed = true;
            case "error" -> errorSeen = true;
            default -> { }
        }
    }

    private static int replaceIfPresent(JsonNode node, int current) {
        return node.canConvertToLong() ? OpenAiChatUsageParser.count(node) : current;
    }

    private void rememberModel(JsonNode node) {
        if (model == null && node.isTextual() && !node.asText().isEmpty()) {
            model = node.asText();
        }
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            return null; // not JSON: nothing to read (content is never logged)
        }
    }

    /**
     * Rebuilds the parts we need from an oversized event: {@code type} and {@code model} from its first bytes,
     * {@code usage} from its last bytes. Unescaped {@code "usage":} only occurs as a real key, since quotes inside
     * JSON strings are escaped.
     */
    private JsonNode fromTruncated(String head, String tail) {
        ObjectNode node = JSON.createObjectNode();
        Matcher type = TYPE.matcher(head);
        if (type.find()) {
            node.put("type", type.group(1));
        }
        Matcher m = MODEL.matcher(head);
        String modelValue = m.find() ? m.group(1) : null;
        JsonNode usageNode = lastObjectAfterKey(tail, "\"usage\":");
        ObjectNode target = switch (endpoint) {
            case RESPONSES -> node.putObject("response");
            case MESSAGES -> "message_start".equals(node.path("type").asText()) ? node.putObject("message") : node;
            case CHAT_COMPLETIONS -> node;
        };
        if (modelValue != null) {
            target.put("model", modelValue);
        }
        if (usageNode != null) {
            target.set("usage", usageNode);
        }
        return node;
    }

    static JsonNode lastObjectAfterKey(String text, String key) {
        int at = text.lastIndexOf(key);
        if (at < 0) {
            return null;
        }
        int open = text.indexOf('{', at + key.length());
        if (open < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return parse(text.substring(open, i + 1));
            }
        }
        return null;
    }
}
