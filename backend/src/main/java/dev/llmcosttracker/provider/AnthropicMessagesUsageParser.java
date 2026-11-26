package dev.llmcosttracker.provider;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reads usage from a non-streaming Anthropic Messages response body.
 *
 * <p>Fields (checked against the Anthropic API reference and prompt-caching guide on 2026-11-26):
 * {@code usage.input_tokens} is only the uncached input after the last cache breakpoint ("total input =
 * cache_read_input_tokens + cache_creation_input_tokens + input_tokens"), so it is stored as reported.
 * {@code usage.cache_read_input_tokens} and {@code usage.cache_creation_input_tokens} are stored separately; when
 * the latter is absent, the per-TTL breakdown {@code usage.cache_creation.ephemeral_5m_input_tokens +
 * ephemeral_1h_input_tokens} is used. {@code usage.output_tokens_details.thinking_tokens} is kept for display
 * only, like OpenAI reasoning tokens.
 */
public final class AnthropicMessagesUsageParser {

    private AnthropicMessagesUsageParser() {}

    public static Usage parse(JsonNode root) {
        if (root == null || !root.isObject()) {
            return Usage.NONE;
        }
        String model = root.path("model").isTextual() ? root.path("model").asText() : null;
        JsonNode usage = root.path("usage");
        int input = OpenAiChatUsageParser.count(usage.path("input_tokens"));
        int output = OpenAiChatUsageParser.count(usage.path("output_tokens"));
        int cacheRead = OpenAiChatUsageParser.count(usage.path("cache_read_input_tokens"));
        int cacheWrite = usage.path("cache_creation_input_tokens").canConvertToLong()
                ? OpenAiChatUsageParser.count(usage.path("cache_creation_input_tokens"))
                : saturatedSum(
                        OpenAiChatUsageParser.count(usage.path("cache_creation").path("ephemeral_5m_input_tokens")),
                        OpenAiChatUsageParser.count(usage.path("cache_creation").path("ephemeral_1h_input_tokens")));
        int thinking = OpenAiChatUsageParser.count(usage.path("output_tokens_details").path("thinking_tokens"));
        return new Usage(model, input, output, cacheRead, cacheWrite, thinking);
    }

    private static int saturatedSum(int a, int b) {
        return (int) Math.min(Integer.MAX_VALUE, (long) a + b);
    }
}
