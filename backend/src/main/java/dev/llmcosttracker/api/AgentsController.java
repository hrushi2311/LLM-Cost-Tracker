package dev.llmcosttracker.api;

import dev.llmcosttracker.attribution.AgentService;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Rename (display name only; the slug never changes) and merge. */
@RestController
@RequestMapping(path = "/api/agents/{agentId}", produces = MediaType.APPLICATION_JSON_VALUE)
public class AgentsController {

    public record RenameRequest(String displayName) {}

    public record MergeRequest(String intoAgentId) {}

    public record AgentRef(UUID id, UUID projectId, String name, String displayName, String source) {}

    private final AgentService agents;
    private final JdbcTemplate jdbc;

    public AgentsController(AgentService agents, JdbcTemplate jdbc) {
        this.agents = agents;
        this.jdbc = jdbc;
    }

    private Optional<AgentRef> find(UUID id) {
        return jdbc.query("SELECT id, project_id, name, display_name, source FROM agent WHERE id = ?",
                (rs, i) -> new AgentRef(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getString(5)), id).stream().findFirst();
    }

    /** Sets {@code displayName}; null or blank clears it, so the slug is shown again. */
    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<AgentRef> rename(@PathVariable UUID agentId, @RequestBody RenameRequest body) {
        String name = body.displayName() == null || body.displayName().isBlank()
                ? null
                : Validation.name("displayName", body.displayName(), 100);
        return Blocking.call(() -> {
            try {
                agents.rename(agentId, name);
            } catch (NoSuchElementException e) {
                throw ApiException.notFound("Unknown agent");
            }
            return find(agentId).orElseThrow(() -> ApiException.notFound("Unknown agent"));
        });
    }

    /** Merges this agent into {@code intoAgentId}: aliases, events and seen-times move over, this agent is deleted. */
    @PostMapping(path = "/merge", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<AgentRef>> merge(@PathVariable UUID agentId, @RequestBody MergeRequest body) {
        UUID into = Validation.uuid("intoAgentId", body.intoAgentId());
        return Blocking.call(() -> {
            try {
                agents.merge(agentId, into);
            } catch (NoSuchElementException e) {
                throw ApiException.notFound(e.getMessage());
            } catch (IllegalArgumentException e) {
                throw ApiException.validation(e.getMessage()); // into itself, or across projects
            }
            return ResponseEntity.ok(find(into).orElseThrow(() -> ApiException.notFound("Unknown agent")));
        });
    }
}
