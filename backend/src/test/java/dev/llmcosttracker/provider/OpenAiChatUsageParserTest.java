package dev.llmcosttracker.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class OpenAiChatUsageParserTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    static byte[] fixture(String name) throws IOException {
        try (InputStream in = OpenAiChatUsageParserTest.class.getResourceAsStream("/fixtures/openai/" + name)) {
            return in.readAllBytes();
        }
    }

    @Test
    void cachedTokensAreSubtractedFromInput() throws IOException {
        Usage u = OpenAiChatUsageParser.parse(JSON.readTree(fixture("chat-completion.json")));

        assertThat(u.model()).isEqualTo("gpt-4o-2024-08-06");
        assertThat(u.inputTokens()).isEqualTo(1117 - 1024);
        assertThat(u.cacheReadTokens()).isEqualTo(1024);
        assertThat(u.outputTokens()).isEqualTo(46);
        assertThat(u.cacheWriteTokens()).isZero();
        assertThat(u.reasoningTokens()).isZero();
    }

    @Test
    void reasoningTokensAreKeptSeparateAndStayInsideOutput() throws IOException {
        Usage u = OpenAiChatUsageParser.parse(JSON.readTree(fixture("chat-completion-reasoning.json")));

        assertThat(u.model()).isEqualTo("o4-mini-2025-04-16");
        assertThat(u.inputTokens()).isEqualTo(512);
        assertThat(u.outputTokens()).isEqualTo(1200); // reasoning included, not added again
        assertThat(u.reasoningTokens()).isEqualTo(1024);
    }

    @Test
    void missingUsageGivesZeros() throws IOException {
        Usage u = OpenAiChatUsageParser.parse(JSON.readTree("{\"error\":{\"message\":\"Rate limit\"}}"));

        assertThat(u).isEqualTo(Usage.NONE);
    }

    @Test
    void missingDetailsObjectsAreTreatedAsZero() throws IOException {
        Usage u = OpenAiChatUsageParser.parse(JSON.readTree(
                "{\"model\":\"gpt-4o\",\"usage\":{\"prompt_tokens\":13,\"completion_tokens\":18,\"total_tokens\":31}}"));

        assertThat(u).isEqualTo(new Usage("gpt-4o", 13, 18, 0, 0, 0));
    }

    @Test
    void cachedNeverExceedsPromptAndNegativeValuesAreIgnored() throws IOException {
        Usage u = OpenAiChatUsageParser.parse(JSON.readTree(
                "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":-5,"
                        + "\"prompt_tokens_details\":{\"cached_tokens\":50}}}"));

        assertThat(u.inputTokens()).isZero();
        assertThat(u.cacheReadTokens()).isEqualTo(10);
        assertThat(u.outputTokens()).isZero();
    }

    @Test
    void nonObjectBodyGivesZeros() throws IOException {
        assertThat(OpenAiChatUsageParser.parse(JSON.readTree("[1,2]"))).isEqualTo(Usage.NONE);
        assertThat(OpenAiChatUsageParser.parse(null)).isEqualTo(Usage.NONE);
    }
}
