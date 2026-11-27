package dev.llmcosttracker.attribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import dev.llmcosttracker.Await;
import dev.llmcosttracker.provider.Endpoint;
import dev.llmcosttracker.proxy.ProxyIntegrationTestBase;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Milestone 4: agent attribution (header, path, fingerprint, unattributed), runs, tags and the path-based key. */
@ExtendWith(OutputCaptureExtension.class)
class AttributionIntegrationTest extends ProxyIntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    private List<Map<String, Object>> agents() {
        return jdbc.queryForList(
                "SELECT id, name, source, fingerprint FROM agent WHERE project_id = ? ORDER BY name", projectId);
    }

    private List<Map<String, Object>> awaitEvents(int n) {
        return Await.until(this::events, rows -> rows.size() >= n, Duration.ofSeconds(15));
    }

    private static String chatBody(String system, String user) throws Exception {
        String messages = (system == null ? "" : "{\"role\":\"system\",\"content\":" + JSON.writeValueAsString(system) + "},")
                + "{\"role\":\"user\",\"content\":" + JSON.writeValueAsString(user) + "}";
        return "{\"model\":\"gpt-4o\",\"messages\":[" + messages + "]}";
    }

    private static String fingerprintOf(Endpoint endpoint, String body) throws Exception {
        return new Fingerprinter(false).fingerprint(endpoint, JSON.readTree(body)).orElseThrow();
    }

    @Test
    void oneClientThreeAgentsOneRun() throws Exception {
        // Acceptance: one HTTP client, three agents identified three different ways, one run ID across all calls.
        for (int i = 0; i < 3; i++) {
            OPENAI.enqueue(jsonResponse(200, fixture("openai/chat-completion.json")));
        }
        String reviewerBody = chatBody("You are the reviewer. Check facts carefully.", "Review the draft");

        rawPost("openai", "/chat/completions", chatBody(null, "Find sources"),
                "X-Tracker-Key", projectKey, "X-Tracker-Run-Id", "run-42", "X-Tracker-Agent", "Researcher");
        rawPost("openai", "/agent/writer/chat/completions", chatBody(null, "Write the draft"),
                "X-Tracker-Key", projectKey, "X-Tracker-Run-Id", "run-42");
        rawPost("openai", "/chat/completions", reviewerBody,
                "X-Tracker-Key", projectKey, "X-Tracker-Run-Id", "run-42");

        awaitEvents(3);
        String fp = fingerprintOf(Endpoint.CHAT_COMPLETIONS, reviewerBody);
        assertThat(agents()).extracting(a -> a.get("name") + "/" + a.get("source")).containsExactlyInAnyOrder(
                "researcher/header",
                "writer/path",
                "agent-" + fp.substring(0, 4) + "/fingerprint");
        assertThat(agents()).filteredOn(a -> "fingerprint".equals(a.get("source")))
                .extracting(a -> a.get("fingerprint")).containsExactly(fp);

        Map<String, Object> run = jdbc.queryForMap("SELECT count(*) AS calls, count(DISTINCT agent_id) AS agents "
                + "FROM usage_event WHERE project_id = ? AND run_id = 'run-42'", projectId);
        assertThat(run).containsEntry("calls", 3L).containsEntry("agents", 3L);
    }

    @Test
    void sameSystemPromptWithDifferentUserMessagesIsOneAgent() throws Exception {
        OPENAI.enqueue(jsonResponse(200, fixture("openai/chat-completion.json")));
        OPENAI.enqueue(jsonResponse(200, fixture("openai/chat-completion.json")));

        rawPost("openai", "/chat/completions", chatBody("You are a planner.", "Plan a trip"), "X-Tracker-Key", projectKey);
        rawPost("openai", "/chat/completions", chatBody("You are a planner.", "Plan a party"), "X-Tracker-Key", projectKey);

        List<Map<String, Object>> events = awaitEvents(2);
        assertThat(events).extracting(e -> e.get("agent_id")).containsOnly(events.get(0).get("agent_id"));
        assertThat(agents()).hasSize(1).first().satisfies(a -> assertThat(a.get("source")).isEqualTo("fingerprint"));
    }

    @Test
    void fingerprintsComeFromAllThreeEndpoints() throws Exception {
        OPENAI.enqueue(jsonResponse(200, fixture("openai/response.json")));
        ANTHROPIC.enqueue(jsonResponse(200, fixture("anthropic/message.json")));
        String responsesBody = "{\"model\":\"o4-mini\",\"instructions\":\"You summarise.\",\"input\":\"text\"}";
        String anthropicBody = "{\"model\":\"claude-sonnet-5-5\",\"max_tokens\":64,"
                + "\"system\":[{\"type\":\"text\",\"text\":\"You review code.\"}],\"messages\":[]}";

        rawPost("openai", "/responses", responsesBody, "X-Tracker-Key", projectKey);
        rawPost("anthropic", "/v1/messages", anthropicBody, "X-Tracker-Key", projectKey);

        awaitEvents(2);
        assertThat(agents()).extracting(a -> a.get("fingerprint")).containsExactlyInAnyOrder(
                fingerprintOf(Endpoint.RESPONSES, responsesBody),
                fingerprintOf(Endpoint.MESSAGES, anthropicBody));
    }

    @Test
    void noSystemPromptTextIsStoredOrLoggedAnywhere(CapturedOutput output) throws Exception {
        String marker = "ZEBRA-" + UUID.randomUUID();
        OPENAI.enqueue(jsonResponse(200, fixture("openai/chat-completion.json")));
        ANTHROPIC.enqueue(jsonResponse(200, fixture("anthropic/message.json")));

        rawPost("openai", "/chat/completions", chatBody("Secret instructions " + marker, "user " + marker),
                "X-Tracker-Key", projectKey);
        rawPost("anthropic", "/v1/messages", "{\"model\":\"claude-sonnet-5-5\",\"system\":\"Also secret " + marker + "\"}",
                "X-Tracker-Key", projectKey);
        awaitEvents(2);

        // Dump every table in the schema as JSON text and search it.
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
        StringBuilder dump = new StringBuilder();
        for (String table : tables) {
            jdbc.queryForList("SELECT to_jsonb(t)::text FROM \"" + table + "\" t", String.class)
                    .forEach(row -> dump.append(row).append('\n'));
        }
        assertThat(tables).contains("agent", "agent_alias", "usage_event", "project");
        assertThat(dump.toString()).doesNotContain(marker, "Secret instructions", "Also secret")
                .contains("fingerprint"); // the fingerprint agents are there, as hashes only
        assertThat(output.getAll()).doesNotContain(marker);
    }

    @Test
    void runIdAndTagsAreStored() throws Exception {
        OPENAI.enqueue(jsonResponse(200, fixture("openai/chat-completion.json")));

        rawPost("openai", "/chat/completions", chatBody(null, "hi"), "X-Tracker-Key", projectKey,
                "X-Tracker-Run-Id", "nightly-2026-11-27", "X-Tracker-Tag-Team", "search",
                "X-Tracker-Tag-Env", "prod");

        Map<String, Object> row = awaitEvents(1).get(0);
        assertThat(row.get("run_id")).isEqualTo("nightly-2026-11-27");
        assertThat(jdbc.queryForObject("SELECT tags = '{\"team\":\"search\",\"env\":\"prod\"}'::jsonb FROM usage_event "
                + "WHERE project_id = ?", Boolean.class, projectId)).isTrue();
    }

    @Test
    void headerAgentNameIsSlugged() throws Exception {
        OPENAI.enqueue(jsonResponse(200, fixture("openai/chat-completion.json")));

        rawPost("openai", "/chat/completions", chatBody("ignored: header wins", "hi"), "X-Tracker-Key", projectKey,
                "X-Tracker-Agent", "  Customer Support Bot!! ");

        awaitEvents(1);
        assertThat(agents()).extracting(a -> a.get("name") + "/" + a.get("source"))
                .containsExactly("customer-support-bot/header");
    }

    @Test
    void projectKeyInThePathWorksWithOnlyABaseUrlChange(CapturedOutput output) throws Exception {
        OPENAI.enqueue(jsonResponse(200, fixture("openai/chat-completion.json")));

        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey("sk-test-NOT-A-REAL-KEY")
                .baseUrl("http://localhost:" + port + "/proxy/k/" + projectKey + "/openai") // the only change
                .maxRetries(0)
                .build();
        client.chat().completions().create(ChatCompletionCreateParams.builder()
                .model(ChatModel.GPT_4O).addUserMessage("hi").build());

        RecordedRequest upstream = OPENAI.takeRequest(5, TimeUnit.SECONDS);
        assertThat(upstream.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(upstream.getHeaders().toString()).doesNotContain(projectKey);
        assertThat(awaitEvents(1).get(0).get("project_id")).isEqualTo(projectId);

        // The access log line for this request is written with the key masked; the key appears in no log line.
        Await.until(output::getAll, all -> all.contains("/proxy/k/[REDACTED]/openai/chat/completions"),
                Duration.ofSeconds(10));
        assertThat(output.getAll()).doesNotContain(projectKey);
    }

    @Test
    void invalidPathKeysAreRejectedAndNeverLogged(CapturedOutput output) throws Exception {
        String bogus = "trk_bogusKeyThatShouldNeverBeLogged123";
        int before = OPENAI.getRequestCount();

        HttpResponse<byte[]> unknownKey = rawPost("k/" + bogus + "/openai", "/chat/completions", "{}");
        HttpResponse<byte[]> unknownProvider = rawPost("k/" + bogus + "/nope", "/x", "{}");
        HttpResponse<byte[]> oddKey = rawPost("k/not-a-trk-format-SECRET/openai", "/chat/completions", "{}");

        assertThat(unknownKey.statusCode()).isEqualTo(401);
        assertThat(unknownProvider.statusCode()).isEqualTo(404);
        assertThat(oddKey.statusCode()).isEqualTo(401);
        assertThat(OPENAI.getRequestCount()).isEqualTo(before);
        Await.until(output::getAll, all -> all.contains("/proxy/k/[REDACTED]/nope/x"), Duration.ofSeconds(10));
        assertThat(output.getAll()).doesNotContain(bogus, "bogusKeyThatShouldNeverBeLogged", "not-a-trk-format-SECRET");
    }
}
