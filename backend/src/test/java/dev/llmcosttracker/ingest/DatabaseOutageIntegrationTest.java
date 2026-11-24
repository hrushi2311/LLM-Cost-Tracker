package dev.llmcosttracker.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import dev.llmcosttracker.Await;
import dev.llmcosttracker.auth.ProjectKeyService;
import dev.llmcosttracker.auth.ProjectRepository;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Milestone 2 acceptance: stopping Postgres mid-test does not break client calls, and the dropped-events counter
 * goes up. Uses its own container (it gets stopped) and its own Spring context.
 */
@DirtiesContext
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "tracker.admin-password=test-admin-password",
            "spring.datasource.hikari.connection-timeout=1000"
        })
class DatabaseOutageIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    static final MockWebServer UPSTREAM = new MockWebServer();

    static {
        POSTGRES.start();
        try {
            UPSTREAM.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("tracker.openai-base-url", () -> UPSTREAM.url("/v1").toString());
    }

    @AfterAll
    static void cleanUp() throws IOException {
        UPSTREAM.shutdown();
        POSTGRES.stop();
    }

    @LocalServerPort
    int port;

    @Autowired
    ProjectRepository projects;

    @Autowired
    ProjectKeyService projectKeys;

    @Autowired
    UsageEventQueue queue;

    @Autowired
    JdbcTemplate jdbc;

    static MockResponse completion() throws IOException {
        try (InputStream in = DatabaseOutageIntegrationTest.class
                .getResourceAsStream("/fixtures/openai/chat-completion.json")) {
            return new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody(new okio.Buffer().write(in.readAllBytes()));
        }
    }

    @Test
    void stoppedDatabaseDoesNotBreakClientCallsAndDroppedEventsAreCounted() throws IOException {
        String key = projects.create("outage-test").key();
        projectKeys.refresh(); // as the 30 s refresh would; the key is now served from memory

        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey("sk-test-NOT-A-REAL-KEY")
                .baseUrl("http://localhost:" + port + "/proxy/openai")
                .putHeader("X-Tracker-Key", key)
                .maxRetries(0)
                .build();
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(ChatModel.GPT_4O).addUserMessage("hi").build();

        // Database up: the event is written.
        UPSTREAM.enqueue(completion());
        client.chat().completions().create(params);
        Await.until(() -> jdbc.queryForObject("SELECT count(*) FROM usage_event", Long.class),
                n -> n == 1, Duration.ofSeconds(15));

        // Database down: calls still succeed, events are dropped and counted.
        POSTGRES.stop();
        long droppedBefore = queue.droppedCount();
        for (int i = 0; i < 3; i++) {
            UPSTREAM.enqueue(completion());
            ChatCompletion c = client.chat().completions().create(params);
            assertThat(c.id()).isEqualTo("chatcmpl-CJrT7q2mX0f3kQzL9vYbN4wE8sHdA");
        }

        long droppedAfter = Await.until(queue::droppedCount, n -> n >= droppedBefore + 3, Duration.ofSeconds(30));
        assertThat(droppedAfter - droppedBefore).isEqualTo(3);
    }
}
