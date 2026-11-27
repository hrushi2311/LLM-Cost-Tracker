package dev.llmcosttracker.attribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.llmcosttracker.provider.Endpoint;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** Slugs, fingerprint extraction/normalisation/masking, resolution order, run ID and tag rules. */
class AttributionUnitTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Fingerprinter plain = new Fingerprinter(false);
    private final Fingerprinter masked = new Fingerprinter(true);

    private static JsonNode json(String s) throws IOException {
        return JSON.readTree(s);
    }

    // --- slugs ---------------------------------------------------------------------------------------------------

    @Test
    void slugRules() {
        assertThat(Slugs.slug("Research Agent", 64)).isEqualTo("research-agent");
        assertThat(Slugs.slug("  --Planner__v2!!--  ", 64)).isEqualTo("planner-v2");
        assertThat(Slugs.slug("Café Bot", 64)).isEqualTo("caf-bot");
        assertThat(Slugs.slug("a---b", 64)).isEqualTo("a-b");
        assertThat(Slugs.slug("!!!", 64)).isNull();
        assertThat(Slugs.slug("", 64)).isNull();
        assertThat(Slugs.slug(null, 64)).isNull();
        assertThat(Slugs.slug("x".repeat(70), 64)).hasSize(64);
        assertThat(Slugs.slug("abc-" + "d".repeat(60), 4)).isEqualTo("abc"); // trailing dash trimmed after cut
    }

    // --- fingerprint extraction ----------------------------------------------------------------------------------

    @Test
    void chatCompletionsUsesAllSystemAndDeveloperMessagesInOrder() throws IOException {
        JsonNode req = json("{\"messages\":["
                + "{\"role\":\"system\",\"content\":\"You are a planner.\"},"
                + "{\"role\":\"user\",\"content\":\"ignored\"},"
                + "{\"role\":\"developer\",\"content\":[{\"type\":\"text\",\"text\":\"Be brief.\"}]},"
                + "{\"role\":\"assistant\",\"content\":\"ignored too\"}]}");

        assertThat(Fingerprinter.systemPrompt(Endpoint.CHAT_COMPLETIONS, req)).isEqualTo("You are a planner.\nBe brief.");
    }

    @Test
    void responsesUsesInstructions() throws IOException {
        assertThat(Fingerprinter.systemPrompt(Endpoint.RESPONSES,
                json("{\"instructions\":\"Answer in French.\",\"input\":\"hi\"}"))).isEqualTo("Answer in French.");
    }

    @Test
    void anthropicUsesSystemStringOrBlocks() throws IOException {
        assertThat(Fingerprinter.systemPrompt(Endpoint.MESSAGES, json("{\"system\":\"You review code.\"}")))
                .isEqualTo("You review code.");
        assertThat(Fingerprinter.systemPrompt(Endpoint.MESSAGES, json("{\"system\":["
                + "{\"type\":\"text\",\"text\":\"You review code.\"},"
                + "{\"type\":\"text\",\"text\":\"Be strict.\",\"cache_control\":{\"type\":\"ephemeral\"}}]}")))
                .isEqualTo("You review code.\nBe strict.");
    }

    @Test
    void sameSystemPromptWithDifferentUserMessagesGivesTheSameFingerprint() throws IOException {
        String a = plain.fingerprint(Endpoint.CHAT_COMPLETIONS, json("{\"messages\":["
                + "{\"role\":\"system\",\"content\":\"You are a planner.\"},{\"role\":\"user\",\"content\":\"Plan A\"}]}"))
                .orElseThrow();
        String b = plain.fingerprint(Endpoint.CHAT_COMPLETIONS, json("{\"messages\":["
                + "{\"role\":\"system\",\"content\":\"  You are   a\\n planner. \"},{\"role\":\"user\",\"content\":\"Plan B\"}]}"))
                .orElseThrow();

        assertThat(a).isEqualTo(b).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void noSystemPromptMeansNoFingerprint() throws IOException {
        assertThat(plain.fingerprint(Endpoint.CHAT_COMPLETIONS,
                json("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))).isEmpty();
        assertThat(plain.fingerprint(Endpoint.MESSAGES, json("{\"system\":\"   \"}"))).isEmpty();
        assertThat(plain.fingerprint(Endpoint.RESPONSES, json("{\"input\":\"hi\"}"))).isEmpty();
    }

    @Test
    void maskingMakesTemplatedPromptsOneAgent() throws IOException {
        String t1 = "Customer 1042 (jane.doe@example.com), ticket 3f2b8c1e-9a4d-4e6f-8b2a-1c3d5e7f9a0b, "
                + "opened 2026-11-27T09:15:00Z, balance 12.50.";
        String t2 = "Customer 77 (sam@corp.io), ticket 00000000-1111-2222-3333-444444444444, "
                + "opened 2025-01-03, balance 9,999.99.";

        assertThat(masked.fingerprint(Endpoint.MESSAGES, json(system(t1))))
                .isEqualTo(masked.fingerprint(Endpoint.MESSAGES, json(system(t2))));
        // Without masking they are different agents...
        assertThat(plain.fingerprint(Endpoint.MESSAGES, json(system(t1))))
                .isNotEqualTo(plain.fingerprint(Endpoint.MESSAGES, json(system(t2))));
        // ...and masking still separates prompts whose words differ.
        assertThat(masked.fingerprint(Endpoint.MESSAGES, json(system("Refund customer 1"))))
                .isNotEqualTo(masked.fingerprint(Endpoint.MESSAGES, json(system("Bill customer 1"))));
    }

    @Test
    void maskPlaceholders() {
        assertThat(Fingerprinter.mask("id 3f2b8c1e-9a4d-4e6f-8b2a-1c3d5e7f9a0b mail a.b@c.io on 2026-11-27 x42 n=3.14"))
                .isEqualTo("id <uuid> mail <email> on <date> x<num> n=<num>");
    }

    private static String system(String text) throws IOException {
        return "{\"system\":" + JSON.writeValueAsString(text) + "}";
    }

    // --- resolution order ----------------------------------------------------------------------------------------

    @Test
    void headerBeatsPathBeatsFingerprintBeatsUnattributed() {
        HttpHeaders withHeader = new HttpHeaders();
        withHeader.add("X-Tracker-Agent", "Researcher");
        HttpHeaders none = new HttpHeaders();

        assertThat(RequestAttribution.agent(withHeader, "writer", () -> "f".repeat(64)))
                .isEqualTo(AgentRef.header("researcher"));
        assertThat(RequestAttribution.agent(none, "Writer%20Bot", () -> "f".repeat(64)))
                .isEqualTo(AgentRef.path("writer-bot"));
        assertThat(RequestAttribution.agent(none, null, () -> "f".repeat(64)))
                .isEqualTo(AgentRef.fingerprint("f".repeat(64)));
        assertThat(RequestAttribution.agent(none, null, () -> null)).isEqualTo(AgentRef.unattributed());
    }

    @Test
    void emptyAfterSlugFallsThroughToTheNextRule() {
        HttpHeaders junk = new HttpHeaders();
        junk.add("X-Tracker-Agent", "!!!");

        assertThat(RequestAttribution.agent(junk, "%20", () -> null)).isEqualTo(AgentRef.unattributed());
    }

    @Test
    void fingerprintIsOnlyComputedWhenNeeded() {
        HttpHeaders withHeader = new HttpHeaders();
        withHeader.add("X-Tracker-Agent", "a");

        RequestAttribution.agent(withHeader, null, () -> {
            throw new AssertionError("fingerprint must not be computed when a header agent is given");
        });
    }

    // --- run ID and tags -----------------------------------------------------------------------------------------

    @Test
    void runIdRules() {
        assertThat(RequestAttribution.runId(headers("X-Tracker-Run-Id", "  run-42  "))).isEqualTo("run-42");
        assertThat(RequestAttribution.runId(headers("X-Tracker-Run-Id", "r".repeat(128)))).hasSize(128);
        assertThat(RequestAttribution.runId(headers("X-Tracker-Run-Id", "r".repeat(129)))).isNull();
        assertThat(RequestAttribution.runId(headers("X-Tracker-Run-Id", "bad\u0007bell"))).isNull();
        assertThat(RequestAttribution.runId(headers("X-Tracker-Run-Id", "  "))).isNull();
        assertThat(RequestAttribution.runId(new HttpHeaders())).isNull();
    }

    @Test
    void tagRules() {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tracker-Tag-Team", "search");
        h.add("x-tracker-tag-Customer Tier", "gold");
        h.add("X-Tracker-Tag-!!!", "dropped: key empty after slug");
        h.add("X-Tracker-Tag-big", "v".repeat(129));
        h.add("X-Tracker-Tag-" + "k".repeat(40), "long key is cut to 32");
        for (int i = 0; i < 12; i++) {
            h.add("X-Tracker-Tag-extra" + i, "x");
        }

        var tags = RequestAttribution.tags(h);

        assertThat(tags).hasSize(10)
                .containsEntry("team", "search")
                .containsEntry("customer-tier", "gold")
                .containsEntry("k".repeat(32), "long key is cut to 32")
                .doesNotContainKey("big");
    }

    private static HttpHeaders headers(String name, String value) {
        HttpHeaders h = new HttpHeaders();
        h.add(name, value);
        return h;
    }
}
