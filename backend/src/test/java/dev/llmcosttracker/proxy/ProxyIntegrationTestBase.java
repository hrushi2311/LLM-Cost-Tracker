package dev.llmcosttracker.proxy;

import dev.llmcosttracker.Await;
import dev.llmcosttracker.PostgresIntegrationTest;
import dev.llmcosttracker.auth.ProjectRepository;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared setup for proxy integration tests: three MockWebServers play OpenAI, Anthropic and a custom
 * OpenAI-compatible provider named {@code localllm}. Every subclass shares one Spring context.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "tracker.admin-password=test-admin-password",
            // Turn up the loggers most likely to print headers, so "never logged" tests are meaningful.
            "logging.level.dev.llmcosttracker=DEBUG",
            "logging.level.org.springframework.web=DEBUG",
            "logging.level.reactor.netty.http=DEBUG"
        })
public abstract class ProxyIntegrationTestBase extends PostgresIntegrationTest {

    protected static final MockWebServer OPENAI = started();
    protected static final MockWebServer ANTHROPIC = started();
    protected static final MockWebServer CUSTOM = started();
    protected static final String CUSTOM_NAME = "localllm";

    private static MockWebServer started() {
        MockWebServer server = new MockWebServer();
        try {
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return server;
    }

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry registry) {
        registry.add("tracker.openai-base-url", () -> OPENAI.url("/v1").toString());
        registry.add("tracker.anthropic-base-url", () -> ANTHROPIC.url("/").toString());
        registry.add("tracker.custom-providers", () -> CUSTOM_NAME + "=" + CUSTOM.url("/v1"));
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected ProjectRepository projects;

    @Autowired
    protected JdbcTemplate jdbc;

    protected final HttpClient http = HttpClient.newHttpClient();
    protected String projectKey;
    protected UUID projectId;

    @BeforeEach
    void createProjectAndPrices() throws InterruptedException {
        new ResourceDatabasePopulator(new ClassPathResource("fixtures/test-prices.sql")).execute(jdbc.getDataSource());
        ProjectRepository.CreatedProject p = projects.create("proxy-test-" + UUID.randomUUID());
        projectKey = p.key();
        projectId = p.id();
        for (MockWebServer server : List.of(OPENAI, ANTHROPIC, CUSTOM)) {
            while (server.takeRequest(10, TimeUnit.MILLISECONDS) != null) {
                // discard requests left over from a previous test
            }
        }
    }

    protected static byte[] fixture(String path) throws IOException {
        try (InputStream in = ProxyIntegrationTestBase.class.getResourceAsStream("/fixtures/" + path)) {
            return in.readAllBytes();
        }
    }

    protected static MockResponse jsonResponse(int status, byte[] body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setHeader("x-request-id", "req_mock_0001")
                .setBody(new okio.Buffer().write(body));
    }

    protected String proxyBase(String provider) {
        return "http://localhost:" + port + "/proxy/" + provider;
    }

    protected List<Map<String, Object>> events() {
        return jdbc.queryForList("SELECT * FROM usage_event WHERE project_id = ? ORDER BY id", projectId);
    }

    protected Map<String, Object> awaitSingleEvent() {
        return Await.until(this::events, rows -> !rows.isEmpty(), Duration.ofSeconds(15)).get(0);
    }

    /** POST to {@code /proxy/{provider}{path}} with header name/value pairs. */
    protected HttpResponse<byte[]> rawPost(String provider, String path, String body, String... headers)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(proxyBase(provider) + path))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
