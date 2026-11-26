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
