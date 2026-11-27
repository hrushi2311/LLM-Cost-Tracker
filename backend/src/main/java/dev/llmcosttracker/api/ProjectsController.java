package dev.llmcosttracker.api;

import dev.llmcosttracker.auth.ProjectKeyService;
import dev.llmcosttracker.auth.ProjectKeys;
import dev.llmcosttracker.auth.ProjectRepository;
import dev.llmcosttracker.auth.ProjectRepository.ProjectRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Projects and their keys. A full key appears exactly once: in the create or rotate response. */
@RestController
@RequestMapping(path = "/api/projects", produces = MediaType.APPLICATION_JSON_VALUE)
public class ProjectsController {

    public record ProjectView(UUID id, String name, String keyMasked, Instant createdAt) {
        static ProjectView of(ProjectRow p) {
            return new ProjectView(p.id(), p.name(), mask(p.keyLast4()), p.createdAt());
        }
    }

    public record CreateProjectRequest(String name) {}

    /** Includes the full key: returned once, never stored or retrievable again. */
    public record ProjectWithKey(UUID id, String name, String key, String keyMasked) {}

    private final ProjectRepository projects;
    private final ProjectKeyService keys;

    public ProjectsController(ProjectRepository projects, ProjectKeyService keys) {
        this.projects = projects;
        this.keys = keys;
    }

    static String mask(String last4) {
        return ProjectKeys.PREFIX + "…" + last4;
    }

    @GetMapping
    public Mono<List<ProjectView>> list() {
        return Blocking.call(() -> projects.list().stream().map(ProjectView::of).toList());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ProjectWithKey> create(@RequestBody CreateProjectRequest body) {
        String name = Validation.name("name", body.name(), 100);
        return Blocking.call(() -> {
            try {
                ProjectRepository.CreatedProject p = projects.create(name);
                return new ProjectWithKey(p.id(), p.name(), p.key(), mask(ProjectKeys.last4(p.key())));
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("A project with that name already exists");
            }
        });
    }

    @PostMapping("/{id}/rotate-key")
    public Mono<ProjectWithKey> rotateKey(@PathVariable UUID id) {
        return Blocking.call(() -> {
            String key = projects.rotateKey(id).orElseThrow(() -> ApiException.notFound("Unknown project"));
            keys.evict(id); // the old key stops working on this instance now, not at the next cache refresh
            ProjectRow p = projects.find(id).orElseThrow(() -> ApiException.notFound("Unknown project"));
            return new ProjectWithKey(p.id(), p.name(), key, mask(p.keyLast4()));
        });
    }
}
