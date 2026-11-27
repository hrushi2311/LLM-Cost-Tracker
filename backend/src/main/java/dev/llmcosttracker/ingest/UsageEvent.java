package dev.llmcosttracker.ingest;

import dev.llmcosttracker.attribution.AgentRef;
import dev.llmcosttracker.provider.Usage;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One recorded call, as offered to the queue. The agent is still a reference (header/path name or fingerprint
 * hash); the batch writer resolves it to an agent id and computes cost. Never holds prompt or response content.
 */
public record UsageEvent(
        UUID projectId,
        AgentRef agent,
        String runId,
        Map<String, String> tags,
        String provider,
        String endpoint,
        String model,
        Usage usage,
        boolean streamed,
        boolean incomplete,
        int statusCode,
        int latencyMs,
        Instant createdAt) {}
