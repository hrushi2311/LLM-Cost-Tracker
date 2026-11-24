package dev.llmcosttracker;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LlmCostTrackerApplication {

    public static void main(String[] args) {
        // All timestamps are UTC. Pinning the JVM zone also stops the JDBC driver from sending a host
        // zone that Postgres rejects (e.g. the legacy alias "Asia/Calcutta"). Budget periods use
        // TRACKER_TIMEZONE explicitly, never the JVM default.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(LlmCostTrackerApplication.class, args);
    }
}
