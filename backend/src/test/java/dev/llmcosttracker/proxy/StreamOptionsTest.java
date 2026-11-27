package dev.llmcosttracker.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StreamOptionsTest {

    private static String inject(String body) {
        return new String(StreamOptions.injectIncludeUsage(body.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }

    @Test
    void addsOnlyTheFieldBeforeTheClosingBraceAndKeepsEverythingElseByteForByte() {
        String body = "{ \"model\" : \"gpt-4o\",\n  \"stream\": true,\n  \"messages\": [{\"role\":\"user\",\"content\":\"}\"}]\n}\n";

        assertThat(inject(body)).isEqualTo(
                "{ \"model\" : \"gpt-4o\",\n  \"stream\": true,\n  \"messages\": [{\"role\":\"user\",\"content\":\"}\"}]\n"
                        + ",\"stream_options\":{\"include_usage\":true}}\n");
    }

    @Test
    void emptyObjectGetsNoComma() {
        assertThat(inject("{ }")).isEqualTo("{ \"stream_options\":{\"include_usage\":true}}");
    }

    @Test
    void nonObjectBodiesAreLeftAlone() {
        assertThat(inject("[1,2]")).isEqualTo("[1,2]");
        assertThat(inject("")).isEmpty();
    }
}
