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
    void redactsProjectKeyInProxyPathsWhateverItsFormat() {
        String out = RedactingMessageConverter.redact(
                "HTTP POST \"/proxy/k/trk_9f8e7d6c5b4a/openai/chat/completions\" and /proxy/k/short-Key_1?x=1");

        assertThat(out)
                .doesNotContain("9f8e7d6c5b4a", "short-Key_1")
                .contains("/proxy/k/[REDACTED]/openai/chat/completions", "/proxy/k/[REDACTED]?x=1");
    }

    @Test
    void redactsPasswordFields() {
        assertThat(RedactingMessageConverter.redact("Decoded [LoginRequest[password=my secret pw]]"))
                .isEqualTo("Decoded [LoginRequest[password=[REDACTED]]]");
        assertThat(RedactingMessageConverter.redact("Encoding [{password=hunter2-long, remember=true}]"))
                .isEqualTo("Encoding [{password=[REDACTED], remember=true}]");
        assertThat(RedactingMessageConverter.redact("body {\"password\":\"p a s s\"}"))
                .isEqualTo("body {\"password\":\"[REDACTED]\"}");
        assertThat(RedactingMessageConverter.redact("Writing \"password=abc123def\""))
                .isEqualTo("Writing \"password=[REDACTED]\"");
        assertThat(RedactingMessageConverter.redact("Encoding [SessionInfo[csrfToken=B6DGtf7x9jFJuEXv]]"))
                .isEqualTo("Encoding [SessionInfo[csrfToken=[REDACTED]]]");
        assertThat(RedactingMessageConverter.redact("X-CSRF-Token: B6DGtf7x9jFJuEXv"))
                .isEqualTo("X-CSRF-Token: [REDACTED]");
    }

    @Test
    void leavesOrdinaryMessagesAlone() {
        String msg = "Dropped 3 usage event(s): queue full (total dropped: 7)";
        assertThat(RedactingMessageConverter.redact(msg)).isEqualTo(msg);
        assertThat(RedactingMessageConverter.redact(null)).isNull();
    }
}
