package dev.llmcosttracker.provider;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reads usage from a non-streaming OpenAI Chat Completions response body.
 *
 * <p>Fields (checked against the OpenAI API reference on 2026-11-24): {@code usage.prompt_tokens} (includes cached
 * tokens), {@code usage.completion_tokens} (includes reasoning tokens),
 * {@code usage.prompt_tokens_details.cached_tokens}, {@code usage.completion_tokens_details.reasoning_tokens}.
 */
public final class OpenAiChatUsageParser {

    private OpenAiChatUsageParser() {}

    public static Usage parse(JsonNode root) {
        if (root == null || !root.isObject()) {
            return Usage.NONE;
        }
        String model = root.path("model").isTextual() ? root.path("model").asText() : null;
        JsonNode usage = root.path("usage");
        int prompt = count(usage.path("prompt_tokens"));
        int completion = count(usage.path("completion_tokens"));
        int cached = count(usage.path("prompt_tokens_details").path("cached_tokens"));
        int reasoning = count(usage.path("completion_tokens_details").path("reasoning_tokens"));
        // Normalise: prompt_tokens includes cached tokens; store only the uncached part as input.
        int uncached = Math.max(0, prompt - cached);
        return new Usage(model, uncached, completion, Math.min(cached, prompt), 0, reasoning);
    }

    static int count(JsonNode node) {
        if (!node.canConvertToLong()) {
            return 0;
        }
        long v = node.asLong();
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, v));
    }
}
