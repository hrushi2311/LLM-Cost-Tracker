package dev.llmcosttracker.attribution;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Turns an {@link AgentRef} into an {@code agent.id}. Called only by the batch writer, inside its transaction.
 *
 * <p>Order: {@code agent_alias} first (the slug, or {@code fp:<hash>}), then the agent itself. Creation uses
 * {@code INSERT … ON CONFLICT (project_id, name)}, so concurrent first sightings of the same agent — from this
 * writer or from another app instance — always end with exactly one row.
 */
@Repository
public class AgentRepository {

    /** Fingerprint names use 4 hex characters, then 6, then 8 on a collision within the project (then longer). */
    static final int[] FINGERPRINT_NAME_LENGTHS = {4, 6, 8, 12, 16, 24, 32, 64};

    private final JdbcTemplate jdbc;

    public AgentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID resolve(UUID projectId, AgentRef ref, Instant seenAt) {
        Optional<UUID> aliased = findAlias(projectId, ref.aliasKey());
        if (aliased.isPresent()) {
            return aliased.get();
        }
        return ref.source() == AgentSource.FINGERPRINT
                ? resolveFingerprint(projectId, ref.fingerprint(), seenAt)
                : upsertNamed(projectId, ref.name(), ref.source(), seenAt);
    }

    Optional<UUID> findAlias(UUID projectId, String alias) {
        return jdbc.query("SELECT agent_id FROM agent_alias WHERE project_id = ? AND alias = ?",
                        (rs, i) -> rs.getObject(1, UUID.class), projectId, alias)
                .stream().findFirst();
    }

    private UUID upsertNamed(UUID projectId, String name, AgentSource source, Instant seenAt) {
        Timestamp ts = Timestamp.from(seenAt);
        return jdbc.queryForObject(
                "INSERT INTO agent (id, project_id, name, source, first_seen, last_seen) VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (project_id, name) DO UPDATE SET last_seen = GREATEST(agent.last_seen, EXCLUDED.last_seen) "
                        + "RETURNING id",
                (rs, i) -> rs.getObject(1, UUID.class),
                UUID.randomUUID(), projectId, name, source.dbValue(), ts, ts);
    }

    private UUID resolveFingerprint(UUID projectId, String hash, Instant seenAt) {
        Optional<UUID> existing = jdbc.query(
                        "SELECT id FROM agent WHERE project_id = ? AND fingerprint = ?",
                        (rs, i) -> rs.getObject(1, UUID.class), projectId, hash)
                .stream().findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        Timestamp ts = Timestamp.from(seenAt);
        for (int length : FINGERPRINT_NAME_LENGTHS) {
            String name = "agent-" + hash.substring(0, Math.min(length, hash.length()));
            if (findAlias(projectId, name).isPresent()) {
                continue; // name taken by a merged agent's alias: treat as a collision
            }
            List<UUID> inserted = jdbc.query(
                    "INSERT INTO agent (id, project_id, name, source, fingerprint, first_seen, last_seen) "
                            + "VALUES (?, ?, ?, 'fingerprint', ?, ?, ?) ON CONFLICT (project_id, name) DO NOTHING RETURNING id",
                    (rs, i) -> rs.getObject(1, UUID.class),
                    UUID.randomUUID(), projectId, name, hash, ts, ts);
            if (!inserted.isEmpty()) {
                return inserted.get(0);
            }
            // Name exists: the same fingerprint created concurrently (use it), or a different agent (collision).
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT id, fingerprint FROM agent WHERE project_id = ? AND name = ?", projectId, name);
            if (hash.equals(row.get("fingerprint"))) {
                return (UUID) row.get("id");
            }
        }
        throw new IllegalStateException("Could not name fingerprint agent: every candidate name is taken");
    }

    /** Moves {@code last_seen} forward (never back) for each agent. */
    public void touch(Map<UUID, Instant> lastSeen) {
        List<Object[]> args = new ArrayList<>(lastSeen.size());
        lastSeen.forEach((id, at) -> args.add(new Object[] {Timestamp.from(at), id}));
        jdbc.batchUpdate("UPDATE agent SET last_seen = GREATEST(last_seen, ?) WHERE id = ?", args);
    }
}
