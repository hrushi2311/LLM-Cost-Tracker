package dev.llmcosttracker.provider;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reads usage from a non-streaming OpenAI Responses API response body.
 *
 * <p>Fields (checked against the OpenAI API reference on 2026-11-26): {@code usage.input_tokens} (includes cached
 * tokens, as for Chat Completions), {@code usage.output_tokens} (includes reasoning tokens),
 * {@code usage.input_tokens_details.cached_tokens}, {@code usage.output_tokens_details.reasoning_tokens}.
 */
public final class OpenAiResponsesUsageParser {

    private OpenAiResponsesUsageParser() {}

    public static Usage parse(JsonNode root) {
        if (root == null || !root.isObject()) {
            return Usage.NONE;
        }
        String model = root.path("model").isTextual() ? root.path("model").asText() : null;
        JsonNode usage = root.path("usage");
        int input = OpenAiChatUsageParser.count(usage.path("input_tokens"));
        int output = OpenAiChatUsageParser.count(usage.path("output_tokens"));
        int cached = OpenAiChatUsageParser.count(usage.path("input_tokens_details").path("cached_tokens"));
        int reasoning = OpenAiChatUsageParser.count(usage.path("output_tokens_details").path("reasoning_tokens"));
        return new Usage(model, Math.max(0, input - cached), output, Math.min(cached, input), 0, reasoning);
    }
}
