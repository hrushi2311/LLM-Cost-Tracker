package dev.llmcosttracker.attribution;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Rename and merge. Exposed through the dashboard API in milestone 6. */
@Service
public class AgentService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public AgentService(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** Sets the display name; the slug never changes, so header and path matching keep working. */
    public void rename(UUID agentId, String displayName) {
        String value = displayName == null || displayName.isBlank() ? null : displayName.trim();
        if (value != null && value.length() > 100) {
            throw new IllegalArgumentException("Display name is longer than 100 characters");
        }
        if (jdbc.update("UPDATE agent SET display_name = ? WHERE id = ?", value, agentId) == 0) {
            throw new IllegalArgumentException("Unknown agent");
        }
    }

    /**
     * Merges agent {@code fromId} into {@code intoId} in one transaction: lock both rows, re-point A's existing
     * aliases to B, add aliases for A's name and fingerprint, move A's events to B, widen B's first/last seen, and
     * delete A. Either everything happens or nothing does.
     */
    public void merge(UUID fromId, UUID intoId) {
        if (fromId.equals(intoId)) {
            throw new IllegalArgumentException("Cannot merge an agent into itself");
        }
        tx.executeWithoutResult(status -> {
            // Lock both rows in a fixed order so two opposite merges cannot deadlock.
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, project_id, name, fingerprint FROM agent WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
                    fromId, intoId);
            Map<String, Object> from = rows.stream().filter(r -> fromId.equals(r.get("id"))).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown agent to merge"));
            Map<String, Object> into = rows.stream().filter(r -> intoId.equals(r.get("id"))).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown agent to merge into"));
            UUID projectId = (UUID) from.get("project_id");
            if (!projectId.equals(into.get("project_id"))) {
                throw new IllegalArgumentException("Agents belong to different projects");
            }

            jdbc.update("UPDATE agent_alias SET agent_id = ? WHERE agent_id = ?", intoId, fromId);
            addAlias(projectId, (String) from.get("name"), intoId);
            if (from.get("fingerprint") != null) {
                addAlias(projectId, "fp:" + from.get("fingerprint"), intoId);
            }
            jdbc.update("UPDATE usage_event SET agent_id = ? WHERE agent_id = ?", intoId, fromId);
            jdbc.update("UPDATE agent SET first_seen = LEAST(agent.first_seen, a.first_seen), "
                    + "last_seen = GREATEST(agent.last_seen, a.last_seen) FROM agent a WHERE agent.id = ? AND a.id = ?",
                    intoId, fromId);
            jdbc.update("DELETE FROM agent WHERE id = ?", fromId);
        });
    }

    private void addAlias(UUID projectId, String alias, UUID agentId) {
        jdbc.update("INSERT INTO agent_alias (project_id, alias, agent_id) VALUES (?, ?, ?) "
                + "ON CONFLICT (project_id, alias) DO UPDATE SET agent_id = EXCLUDED.agent_id", projectId, alias, agentId);
    }
}
