package dev.llmcosttracker.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** UTC wall clock; injected so time-dependent logic (sessions, rate limits, budget periods) can be tested. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
