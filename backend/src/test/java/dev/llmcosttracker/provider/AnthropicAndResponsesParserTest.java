package dev.llmcosttracker.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class AnthropicAndResponsesParserTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void anthropicCacheFieldsMayBeNullOrAbsent() throws IOException {
        Usage u = AnthropicMessagesUsageParser.parse(JSON.readTree(
                "{\"model\":\"claude-haiku-5-5\",\"usage\":{\"input_tokens\":12,\"output_tokens\":7,"
                        + "\"cache_creation_input_tokens\":null,\"cache_read_input_tokens\":null}}"));

        assertThat(u).isEqualTo(new Usage("claude-haiku-5-5", 12, 7, 0, 0, 0));
    }

    @Test
    void anthropicCacheWriteFallsBackToPerTtlBreakdown() throws IOException {
        Usage u = AnthropicMessagesUsageParser.parse(JSON.readTree(
                "{\"usage\":{\"input_tokens\":1,\"output_tokens\":1,"
                        + "\"cache_creation\":{\"ephemeral_5m_input_tokens\":300,\"ephemeral_1h_input_tokens\":200}}}"));

        assertThat(u.cacheWriteTokens()).isEqualTo(500);
    }

    @Test
    void anthropicThinkingTokensAreDisplayOnly() throws IOException {
        Usage u = AnthropicMessagesUsageParser.parse(JSON.readTree(
                "{\"usage\":{\"input_tokens\":10,\"output_tokens\":900,"
                        + "\"output_tokens_details\":{\"thinking_tokens\":700}}}"));

        assertThat(u.outputTokens()).isEqualTo(900);
        assertThat(u.reasoningTokens()).isEqualTo(700);
    }

    @Test
    void anthropicThinkingIsBilledInsideOutputTokensNotOnTop() throws IOException {
        // Anthropic docs example: output_tokens 348, of which thinking_tokens 312. output_tokens is the inclusive
        // billing total, so cost uses 348 output tokens; the 312 must not be added again.
        Usage u = AnthropicMessagesUsageParser.parse(JSON.readTree(
                "{\"usage\":{\"input_tokens\":10,\"output_tokens\":348,"
                        + "\"output_tokens_details\":{\"thinking_tokens\":312}}}"));
        dev.llmcosttracker.pricing.ModelPrice price = new dev.llmcosttracker.pricing.ModelPrice("anthropic", "m",
                java.time.LocalDate.parse("2024-01-01"), new java.math.BigDecimal("4"), new java.math.BigDecimal("20"),
                null, null);

        // 10*4 + 348*20 = 40 + 6960 = 7000 -> 0.007 (adding thinking again would give 0.01324)
        assertThat(dev.llmcosttracker.pricing.CostCalculator.cost(u, price))
                .isEqualTo(new java.math.BigDecimal("0.00700000"));
        assertThat(u.reasoningTokens()).isEqualTo(312);
    }

    @Test
    void anthropicErrorBodyGivesZeros() throws IOException {
        Usage u = AnthropicMessagesUsageParser.parse(JSON.readTree(
                "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"));

        assertThat(u).isEqualTo(Usage.NONE);
    }

    @Test
    void responsesWithoutDetailsObjects() throws IOException {
        Usage u = OpenAiResponsesUsageParser.parse(JSON.readTree(
                "{\"model\":\"gpt-4o\",\"usage\":{\"input_tokens\":30,\"output_tokens\":5,\"total_tokens\":35}}"));

        assertThat(u).isEqualTo(new Usage("gpt-4o", 30, 5, 0, 0, 0));
    }

    @Test
    void responsesInProgressWithNullUsage() throws IOException {
        Usage u = OpenAiResponsesUsageParser.parse(JSON.readTree(
                "{\"object\":\"response\",\"status\":\"queued\",\"model\":\"gpt-4o\",\"usage\":null}"));

        assertThat(u).isEqualTo(new Usage("gpt-4o", 0, 0, 0, 0, 0));
    }

    @Test
    void endpointClassification() {
        assertThat(Endpoint.classify(Dialect.OPENAI, HttpMethod.POST, "/chat/completions")).contains(Endpoint.CHAT_COMPLETIONS);
        assertThat(Endpoint.classify(Dialect.OPENAI, HttpMethod.POST, "/responses")).contains(Endpoint.RESPONSES);
        assertThat(Endpoint.classify(Dialect.OPENAI, HttpMethod.POST, "/responses/")).contains(Endpoint.RESPONSES);
        assertThat(Endpoint.classify(Dialect.ANTHROPIC, HttpMethod.POST, "/v1/messages")).contains(Endpoint.MESSAGES);

        assertThat(Endpoint.classify(Dialect.OPENAI, HttpMethod.GET, "/responses/resp_123")).isEmpty();
        assertThat(Endpoint.classify(Dialect.OPENAI, HttpMethod.POST, "/embeddings")).isEmpty();
        assertThat(Endpoint.classify(Dialect.ANTHROPIC, HttpMethod.POST, "/v1/messages/count_tokens")).isEmpty();
        assertThat(Endpoint.classify(Dialect.ANTHROPIC, HttpMethod.POST, "/v1/messages/batches")).isEmpty();
        assertThat(Endpoint.classify(Dialect.ANTHROPIC, HttpMethod.POST, "/chat/completions")).isEmpty();
    }
}
