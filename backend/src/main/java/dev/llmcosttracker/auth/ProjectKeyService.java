package dev.llmcosttracker.auth;

import dev.llmcosttracker.config.RateLimitedLog;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Resolves {@code X-Tracker-Key} to a project id. Keys are cached in memory (refreshed every 30 s) so the proxy
 * keeps serving known projects while Postgres is down; only a cache miss touches the database.
 */
@Service
public class ProjectKeyService {

    private static final Logger log = LoggerFactory.getLogger(ProjectKeyService.class);

    private final ProjectRepository projects;
    private final RateLimitedLog refreshFailures = new RateLimitedLog(log);
    private volatile Map<String, UUID> byHash = Map.of();

    public ProjectKeyService(ProjectRepository projects) {
        this.projects = projects;
    }

    /** Empty when the key is missing or unknown; errors only when an uncached key cannot be checked. */
    public Mono<UUID> resolve(String key) {
        if (key == null || key.isBlank()) {
            return Mono.empty();
        }
        String hash = ProjectKeys.hash(key.trim());
        UUID cached = byHash.get(hash);
        if (cached != null) {
            return Mono.just(cached);
        }
        return Mono.fromCallable(() -> projects.findIdByKeyHash(hash).orElse(null))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(id -> remember(hash, id));
    }

    private synchronized void remember(String hash, UUID id) {
        Map<String, UUID> next = new java.util.HashMap<>(byHash);
        next.put(hash, id);
        byHash = Map.copyOf(next);
    }

    @Scheduled(initialDelay = 0, fixedDelay = 30_000)
    public void refresh() {
        try {
            byHash = Map.copyOf(projects.loadKeyHashes());
        } catch (RuntimeException e) {
            refreshFailures.warn("Could not refresh project keys; keeping the cached set: {}", e.getMessage());
        }
    }
}
