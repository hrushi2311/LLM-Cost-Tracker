package dev.llmcosttracker.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RedactingMessageConverterTest {

    @Test
    void redactsHeaderValuesInCommonFormats() {
        String out = RedactingMessageConverter.redact(
                "headers=[Authorization:\"Bearer sk-abc123def456ghi\", x-api-key: sk-ant-api03-xyzxyzxyz, "
                        + "X-Tracker-Key=trk_9f8e7d6c5b4a, X-Tracker-Run-Id: run-42]");

        assertThat(out)
                .doesNotContain("sk-abc123def456ghi", "api03-xyzxyzxyz", "9f8e7d6c5b4a", "run-42")
                .contains("Authorization:\"Bearer [REDACTED]", "x-api-key: [REDACTED]", "X-Tracker-Key=[REDACTED]");
    }

    @Test
    void redactsBareKeysAndBearerTokens() {
        String out = RedactingMessageConverter.redact(
                "using sk-proj-AAAAbbbbCCCC and trk_ZZZZyyyyXXXX then bearer eyJhbGciOi.payload.sig");

        assertThat(out).isEqualTo("using sk-[REDACTED] and trk_[REDACTED] then bearer [REDACTED]");
    }

    @Test
    void leavesOrdinaryMessagesAlone() {
        String msg = "Dropped 3 usage event(s): queue full (total dropped: 7)";
        assertThat(RedactingMessageConverter.redact(msg)).isEqualTo(msg);
        assertThat(RedactingMessageConverter.redact(null)).isNull();
    }
}
