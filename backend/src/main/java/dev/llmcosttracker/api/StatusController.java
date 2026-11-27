package dev.llmcosttracker.api;

import dev.llmcosttracker.config.TrackerProperties;
import dev.llmcosttracker.ingest.UsageEventQueue;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** For the dashboard's live indicator (polled every 10 s): version, last event, drops and queue depth. */
@RestController
public class StatusController {

    public record Status(String version, Instant lastEventAt, long eventsDropped, int queueDepth, int queueCapacity,
            String timezone) {}

    private final DashboardQueries queries;
    private final UsageEventQueue queue;
    private final TrackerProperties props;
    private final String version;

    public StatusController(DashboardQueries queries, UsageEventQueue queue, TrackerProperties props,
            ObjectProvider<BuildProperties> build) {
        this.queries = queries;
        this.queue = queue;
        this.props = props;
        BuildProperties b = build.getIfAvailable();
        this.version = b == null ? "dev" : b.getVersion();
    }

    /** {@code projectId} narrows {@code lastEventAt} to one project. Drop and queue figures are always global. */
    @GetMapping(path = "/api/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Status> status(@RequestParam(required = false) UUID projectId) {
        return Blocking.call(() -> projectId == null ? queries.lastEventAt() : queries.lastEventAt(projectId))
                .map(java.util.Optional::of)
                .defaultIfEmpty(java.util.Optional.empty())
                .onErrorReturn(java.util.Optional.empty()) // database down: still report queue and drops
                .map(last -> new Status(version, last.orElse(null), queue.droppedCount(), queue.depth(),
                        props.queueCapacity(), props.timezone().getId()));
    }
}
