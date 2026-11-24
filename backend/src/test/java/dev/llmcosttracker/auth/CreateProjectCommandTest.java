package dev.llmcosttracker.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.llmcosttracker.PostgresIntegrationTest;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/** Same context configuration as SkeletonIntegrationTest, so the Spring context is reused. */
@AutoConfigureWebTestClient(timeout = "PT30S")
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "tracker.admin-password=test-admin-password")
class CreateProjectCommandTest extends PostgresIntegrationTest {

    @Autowired
    ProjectRepository projects;

    @Autowired
    ProjectKeyService projectKeys;

    private record Result(int exitCode, String output) {}

    private Result run(String... args) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        CreateProjectCommand command = new CreateProjectCommand(projects, new PrintStream(buffer, true, StandardCharsets.UTF_8));
        command.run(new DefaultApplicationArguments(args));
        return new Result(command.getExitCode(), buffer.toString(StandardCharsets.UTF_8));
    }

    @Test
    void createsProjectAndPrintsAWorkingKeyOnce() {
        String name = "cli-" + UUID.randomUUID();

        Result result = run("--create-project=" + name);

        assertThat(result.exitCode()).isZero();
        String key = result.output().lines().filter(l -> l.startsWith("trk_")).findFirst().orElseThrow();
        UUID id = projectKeys.resolve(key).block();
        assertThat(id).isNotNull();
        assertThat(result.output()).contains("Created project \"" + name + "\"", "shown only once");
    }

    @Test
    void duplicateNameFailsWithExitCode1() {
        String name = "cli-dup-" + UUID.randomUUID();
        run("--create-project=" + name);

        Result again = run("--create-project=" + name);

        assertThat(again.exitCode()).isEqualTo(1);
        assertThat(again.output()).contains("already exists").doesNotContain("trk_");
    }

    @Test
    void emptyNameShowsUsage() {
        Result result = run("--create-project=");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).startsWith("Usage:");
    }

    @Test
    void detectsTheFlag() {
        assertThat(CreateProjectCommand.isRequested(new String[] {"--create-project=x"})).isTrue();
        assertThat(CreateProjectCommand.isRequested(new String[] {"--server.port=1"})).isFalse();
    }
}
