package dev.llmcosttracker.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Tracker settings bound from {@code tracker.*} properties, i.e. {@code TRACKER_*} environment variables.
 * Further settings are added here as the milestones that use them land.
 *
 * @param adminPassword dashboard login password; the app refuses to start if it is empty or shorter than 12 characters
 */
@Validated
@ConfigurationProperties(prefix = "tracker")
public record TrackerProperties(
        @NotBlank(message = "TRACKER_ADMIN_PASSWORD must be set")
        @Size(min = 12, message = "TRACKER_ADMIN_PASSWORD must be at least 12 characters")
        String adminPassword) {

    @Override
    public String toString() {
        return "TrackerProperties[adminPassword=***]";
    }
}
