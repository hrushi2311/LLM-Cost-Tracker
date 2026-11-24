package dev.llmcosttracker;

import static org.assertj.core.api.Assertions.assertThat;

import dev.llmcosttracker.config.TrackerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** The app must refuse to start when TRACKER_ADMIN_PASSWORD is empty or shorter than 12 characters. */
class AdminPasswordValidationTest {

    @Configuration
    @EnableConfigurationProperties(TrackerProperties.class)
    static class Config {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    @Test
    void refusesToStartWithoutPassword() {
        runner.run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void refusesToStartWithEmptyPassword() {
        runner.withPropertyValues("tracker.admin-password=").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void refusesToStartWithShortPassword() {
        runner.withPropertyValues("tracker.admin-password=12345678901").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void startsWithTwelveCharacterPassword() {
        runner.withPropertyValues("tracker.admin-password=123456789012").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void toStringNeverRevealsPassword() {
        runner.withPropertyValues("tracker.admin-password=super-secret-password")
                .run(ctx -> assertThat(ctx.getBean(TrackerProperties.class).toString())
                        .doesNotContain("super-secret")
                        .contains("adminPassword=***"));
    }
}
