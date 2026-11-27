package dev.llmcosttracker.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Tracker settings bound from {@code tracker.*} properties, i.e. {@code TRACKER_*} environment variables
 * (for example {@code TRACKER_OPENAI_BASE_URL} binds to {@code openaiBaseUrl}).
 *
 * @param adminPassword     dashboard login password; startup fails if empty or shorter than 12 characters
 * @param requireProjectKey reject proxy calls without a valid {@code X-Tracker-Key}
 * @param openaiBaseUrl     OpenAI upstream base URL
 * @param anthropicBaseUrl  Anthropic upstream base URL
 * @param customProviders   OpenAI-compatible providers as {@code name=url} pairs, comma separated (may be null)
 * @param connectTimeout    upstream connect timeout
 * @param upstreamTimeout   upstream response timeout
 * @param maxRequestBody    largest request body the proxy accepts
 * @param queueCapacity     size of the in-memory usage event queue
 * @param fingerprintEnabled discover agents from a SHA-256 fingerprint of the system prompt
 * @param fingerprintMask   mask numbers, UUIDs, ISO dates and emails before fingerprinting
 * @param accessLog         write an HTTP access log (project keys in paths are redacted)
 * @param openaiInjectStreamUsage add stream_options.include_usage to streamed Chat Completions requests that
 *                          do not set stream_options (clients then get one extra final chunk)
 * @param timezone          zone for budget periods and day buckets on the dashboard ({@code TRACKER_TIMEZONE})
 */
@Validated
@ConfigurationProperties(prefix = "tracker")
public record TrackerProperties(
        @NotBlank(message = "TRACKER_ADMIN_PASSWORD must be set")
        @Size(min = 12, message = "TRACKER_ADMIN_PASSWORD must be at least 12 characters")
        String adminPassword,
        @DefaultValue("true") boolean requireProjectKey,
        @NotNull @DefaultValue("https://api.openai.com/v1") URI openaiBaseUrl,
        @NotNull @DefaultValue("https://api.anthropic.com") URI anthropicBaseUrl,
        String customProviders,
        @NotNull @DefaultValue("10s") Duration connectTimeout,
        @NotNull @DefaultValue("10m") Duration upstreamTimeout,
        @NotNull @DefaultValue("20MB") DataSize maxRequestBody,
        @Min(1) @DefaultValue("10000") int queueCapacity,
        @DefaultValue("true") boolean fingerprintEnabled,
        @DefaultValue("false") boolean fingerprintMask,
        @DefaultValue("false") boolean accessLog,
        @DefaultValue("true") boolean openaiInjectStreamUsage,
        @NotNull @DefaultValue("UTC") ZoneId timezone) {

    @Override
    public String toString() {
        return "TrackerProperties[adminPassword=***, requireProjectKey=" + requireProjectKey
                + ", openaiBaseUrl=" + openaiBaseUrl + ", anthropicBaseUrl=" + anthropicBaseUrl
                + ", customProviders=" + customProviders
                + ", connectTimeout=" + connectTimeout + ", upstreamTimeout=" + upstreamTimeout
                + ", maxRequestBody=" + maxRequestBody + ", queueCapacity=" + queueCapacity
                + ", fingerprintEnabled=" + fingerprintEnabled + ", fingerprintMask=" + fingerprintMask
                + ", accessLog=" + accessLog + ", openaiInjectStreamUsage=" + openaiInjectStreamUsage + ", timezone=" + timezone + "]";
    }
}
