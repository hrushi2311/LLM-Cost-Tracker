package dev.llmcosttracker.provider;

/**
 * Normalised token counts for one call.
 *
 * @param model           model reported by the response, or null
 * @param inputTokens     uncached input, billed at the full input rate
 * @param outputTokens    output tokens (for OpenAI this already includes reasoning tokens)
 * @param cacheReadTokens input tokens served from the prompt cache
 * @param cacheWriteTokens input tokens written to the prompt cache
 * @param reasoningTokens reasoning tokens, for display only; never added to cost
 */
public record Usage(
        String model,
        int inputTokens,
        int outputTokens,
        int cacheReadTokens,
        int cacheWriteTokens,
        int reasoningTokens) {

    public static final Usage NONE = new Usage(null, 0, 0, 0, 0, 0);
}
