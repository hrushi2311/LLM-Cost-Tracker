package dev.llmcosttracker.ingest;

import dev.llmcosttracker.provider.Usage;
import java.time.Instant;
import java.util.UUID;

/** One recorded call, as offered to the queue. Cost is computed later by the batch writer. Never holds content. */
public record UsageEvent(
        UUID projectId,
        UUID agentId,
        String runId,
        String provider,
        String endpoint,
        String model,
        Usage usage,
        boolean streamed,
        boolean incomplete,
        int statusCode,
        int latencyMs,
        Instant createdAt) {}
