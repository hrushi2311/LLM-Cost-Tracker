package dev.llmcosttracker.auth;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectRepository {

    /** A newly created project and its full key, which is never retrievable again. */
    public record CreatedProject(UUID id, String name, String key) {}

    private final JdbcTemplate jdbc;

    public ProjectRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CreatedProject create(String name) {
        UUID id = UUID.randomUUID();
        String key = ProjectKeys.generate();
        jdbc.update("INSERT INTO project (id, name, key_hash, key_last4) VALUES (?, ?, ?, ?)",
                id, name, ProjectKeys.hash(key), ProjectKeys.last4(key));
        return new CreatedProject(id, name, key);
    }

    /** A project as listed on the dashboard: the key is only ever shown masked. */
    public record ProjectRow(UUID id, String name, String keyLast4, java.time.Instant createdAt) {}

    public java.util.List<ProjectRow> list() {
        return jdbc.query("SELECT id, name, key_last4, created_at FROM project ORDER BY created_at, name",
                (rs, i) -> new ProjectRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant()));
    }

    public Optional<ProjectRow> find(UUID id) {
        return jdbc.query("SELECT id, name, key_last4, created_at FROM project WHERE id = ?",
                        (rs, i) -> new ProjectRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                                rs.getTimestamp(4).toInstant()), id)
                .stream().findFirst();
    }

    public boolean exists(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM project WHERE id = ?)",
                Boolean.class, id));
    }

    /** Replaces the key; the old hash is gone from the table at once. Empty if the project does not exist. */
    public Optional<String> rotateKey(UUID id) {
        String key = ProjectKeys.generate();
        int updated = jdbc.update("UPDATE project SET key_hash = ?, key_last4 = ? WHERE id = ?",
                ProjectKeys.hash(key), ProjectKeys.last4(key), id);
        return updated == 0 ? Optional.empty() : Optional.of(key);
    }

    public Optional<UUID> findIdByKeyHash(String keyHash) {
        return jdbc.query("SELECT id FROM project WHERE key_hash = ?",
                        (rs, i) -> rs.getObject(1, UUID.class), keyHash)
                .stream().findFirst();
    }

    /** key_hash → project id for every project. */
    public Map<String, UUID> loadKeyHashes() {
        Map<String, UUID> out = new HashMap<>();
        jdbc.query("SELECT key_hash, id FROM project",
                rs -> { out.put(rs.getString(1), rs.getObject(2, UUID.class)); });
        return out;
    }
}
