package dev.llmcosttracker.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.llmcosttracker.pricing.CostCalculator;
import dev.llmcosttracker.pricing.ModelPrice;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Milestone 3 acceptance: one real-shaped fixture per recorded endpoint, parsed and priced, with every expected
 * cost worked out by hand below. Prices are made-up test values (see fixtures/test-prices.sql).
 */
class EndpointFixtureCostTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode fixture(String path) throws IOException {
        try (InputStream in = EndpointFixtureCostTest.class.getResourceAsStream("/fixtures/" + path)) {
            return JSON.readTree(in);
        }
    }

    private static ModelPrice price(String in, String out, String cacheRead, String cacheWrite) {
        return new ModelPrice("p", "m", LocalDate.parse("2024-01-01"), new BigDecimal(in), new BigDecimal(out),
                cacheRead == null ? null : new BigDecimal(cacheRead),
                cacheWrite == null ? null : new BigDecimal(cacheWrite));
    }

    @Test
    void openAiChatCompletions() throws IOException {
        Usage u = OpenAiChatUsageParser.parse(fixture("openai/chat-completion.json"));

        // prompt 1117 includes 1024 cached -> 93 uncached; completion 46.
        assertThat(u).isEqualTo(new Usage("gpt-4o-2024-08-06", 93, 46, 1024, 0, 0));
        // $2 in / $8 out / $0.50 cache read per Mtok:
        // 93*2 + 46*8 + 1024*0.5 = 186 + 368 + 512 = 1066 -> 1066 / 1e6 = 0.001066
        assertThat(CostCalculator.cost(u, price("2", "8", "0.5", null))).isEqualTo(new BigDecimal("0.00106600"));
    }

    @Test
    void openAiResponses() throws IOException {
        Usage u = OpenAiResponsesUsageParser.parse(fixture("openai/response.json"));

        // input 1500 includes 1024 cached -> 476 uncached; output 900 includes 640 reasoning.
        assertThat(u).isEqualTo(new Usage("o4-mini-2025-04-16", 476, 900, 1024, 0, 640));
        // $1 in / $4 out, no cache price -> cache read falls back to the input price ($1):
        // 476*1 + 900*4 + 1024*1 = 476 + 3600 + 1024 = 5100 -> 0.0051 (reasoning not added again)
        assertThat(CostCalculator.cost(u, price("1", "4", null, null))).isEqualTo(new BigDecimal("0.00510000"));
    }

    @Test
    void anthropicMessages() throws IOException {
        Usage u = AnthropicMessagesUsageParser.parse(fixture("anthropic/message.json"));

        // input_tokens 2095 already excludes cache -> stored as reported; cache read 4102; cache write 2051.
        assertThat(u).isEqualTo(new Usage("claude-sonnet-5-5", 2095, 503, 4102, 2051, 0));
        // $4 in / $20 out / $0.40 cache read / $5 cache write per Mtok:
        // 2095*4 + 503*20 + 4102*0.4 + 2051*5 = 8380 + 10060 + 1640.8 + 10255 = 30335.8 -> 0.0303358
        assertThat(CostCalculator.cost(u, price("4", "20", "0.4", "5"))).isEqualTo(new BigDecimal("0.03033580"));
    }

    @Test
    void customProviderUsesTheOpenAiParserWithItsOwnPrices() throws IOException {
        Usage u = OpenAiChatUsageParser.parse(fixture("openai/chat-completion.json"));

        // Same tokens as the OpenAI case, priced at the custom provider's $0.20 / $0.40 / $0.10:
        // 93*0.2 + 46*0.4 + 1024*0.1 = 18.6 + 18.4 + 102.4 = 139.4 -> 0.0001394
        assertThat(CostCalculator.cost(u, price("0.2", "0.4", "0.1", null))).isEqualTo(new BigDecimal("0.00013940"));
    }
}
