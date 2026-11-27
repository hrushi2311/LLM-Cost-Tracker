package dev.llmcosttracker;

import dev.llmcosttracker.auth.CreateProjectCommand;
import dev.llmcosttracker.demo.SeedDemoCommand;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class LlmCostTrackerApplication {

    public static void main(String[] args) {
        // All timestamps are UTC. Pinning the JVM zone also stops the JDBC driver from sending a host
        // zone that Postgres rejects (e.g. the legacy alias "Asia/Calcutta"). Budget periods use
        // TRACKER_TIMEZONE explicitly, never the JVM default.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication app = new SpringApplication(LlmCostTrackerApplication.class);
        if (CreateProjectCommand.isRequested(args) || SeedDemoCommand.isRequested(args)) {
            // One-shot CLI (--create-project, --seed-demo): do the work, print the key, exit. No web server.
            app.setWebApplicationType(WebApplicationType.NONE);
            System.exit(SpringApplication.exit(app.run(args)));
        }
        app.run(args);
    }
}
