package dev.llmcosttracker.budget;

import java.math.BigDecimal;
import java.util.UUID;

/** One {@code budget} row. {@code agentId} null means the whole project. */
public record Budget(UUID id, UUID projectId, UUID agentId, String period, BigDecimal limitUsd, int alertPct,
        boolean enforce) {}
