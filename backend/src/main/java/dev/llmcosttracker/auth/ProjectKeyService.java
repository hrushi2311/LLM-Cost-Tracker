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
    private volatile long generation;

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
        long seen = generation;
        return Mono.fromCallable(() -> projects.findIdByKeyHash(hash).orElse(null))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(id -> remember(hash, id, seen));
    }

    private synchronized void remember(String hash, UUID id, long seen) {
        if (generation != seen) {
            return; // a key was rotated meanwhile: do not cache what may be the old key
        }
        Map<String, UUID> next = new java.util.HashMap<>(byHash);
        next.put(hash, id);
        byHash = Map.copyOf(next);
    }

    /** Drops every cached key of a project (after a rotation); the new key is then looked up on first use. */
    public synchronized void evict(UUID projectId) {
        Map<String, UUID> next = new java.util.HashMap<>(byHash);
        next.values().removeIf(projectId::equals);
        byHash = Map.copyOf(next);
        generation++;
    }

    /**
     * Reloads every key hash from the database. Called on schedule and right after a key rotation, so the old key
     * stops working on this instance immediately.
     */
    @Scheduled(initialDelay = 0, fixedDelay = 30_000)
    public void refresh() {
        try {
            long seen = generation;
            Map<String, UUID> loaded = Map.copyOf(projects.loadKeyHashes());
            synchronized (this) {
                if (generation == seen) { // an eviction during the load wins; the next refresh catches up
                    byHash = loaded;
                }
            }
        } catch (RuntimeException e) {
            refreshFailures.warn("Could not refresh project keys; keeping the cached set: {}", e.getMessage());
        }
    }
}
