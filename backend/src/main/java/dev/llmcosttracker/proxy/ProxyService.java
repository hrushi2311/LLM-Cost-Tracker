package dev.llmcosttracker.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.llmcosttracker.attribution.Fingerprinter;
import dev.llmcosttracker.attribution.RequestAttribution;
import dev.llmcosttracker.auth.ProjectKeyService;
import dev.llmcosttracker.config.TrackerProperties;
import dev.llmcosttracker.ingest.UsageEvent;
import dev.llmcosttracker.ingest.UsageEventQueue;
import dev.llmcosttracker.provider.Endpoint;
import dev.llmcosttracker.provider.AnthropicMessagesUsageParser;
import dev.llmcosttracker.provider.OpenAiChatUsageParser;
import dev.llmcosttracker.provider.Dialect;
import dev.llmcosttracker.provider.OpenAiResponsesUsageParser;
import dev.llmcosttracker.provider.StreamUsageTracker;
import dev.llmcosttracker.provider.Usage;
import dev.llmcosttracker.proxy.ProviderRegistry.Provider;
import io.netty.handler.timeout.ReadTimeoutException;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * Forwards a request to the configured upstream and relays the response byte for byte. For recorded endpoints a
 * copy of the body is parsed after the last byte has been sent, and a {@link UsageEvent} is offered to the queue.
 * Nothing on this path touches the database except a project-key cache miss.
 */
@Service
public class ProxyService {

    private static final Logger log = LoggerFactory.getLogger(ProxyService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] EMPTY = new byte[0];

    private final ProviderRegistry providers;
    private final ProjectKeyService projectKeys;
    private final WebClient upstream;
    private final UsageEventQueue queue;
    private final TrackerProperties props;
    private final Fingerprinter fingerprinter;

    public ProxyService(ProviderRegistry providers, ProjectKeyService projectKeys, WebClient upstreamWebClient,
            UsageEventQueue queue, TrackerProperties props) {
        this.providers = providers;
        this.projectKeys = projectKeys;
        this.upstream = upstreamWebClient;
        this.queue = queue;
        this.props = props;
        this.fingerprinter = props.fingerprintEnabled() ? new Fingerprinter(props.fingerprintMask()) : null;
    }

    /** Thrown when an uncached project key cannot be checked because the database is unavailable. */
    private static final class KeyCheckUnavailable extends RuntimeException {
        KeyCheckUnavailable(Throwable cause) {
            super(cause);
        }
    }

    public Mono<Void> handle(ServerWebExchange exchange) {
        long startNanos = System.nanoTime();
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();

        Optional<ProxyRoute> route = ProxyRoute.parse(request.getURI().getRawPath());
        if (route.isEmpty()) {
            return ProxyErrors.write(response, HttpStatus.NOT_FOUND, "not_found",
                    "Expected /proxy/{provider}/{path}");
        }
        Optional<Provider> provider = providers.find(route.get().provider());
        if (provider.isEmpty()) {
            return ProxyErrors.write(response, HttpStatus.NOT_FOUND, "unknown_provider",
                    "Unknown provider. Configured providers: " + String.join(", ", providers.keys()));
        }
        Optional<URI> target = route.get().upstreamUri(provider.get().baseUrl(), request.getURI().getRawQuery());
        if (target.isEmpty()) {
            return ProxyErrors.write(response, HttpStatus.BAD_REQUEST, "invalid_path", "Invalid upstream path");
        }
        long declaredLength = request.getHeaders().getContentLength();
        if (declaredLength > props.maxRequestBody().toBytes()) {
            return tooLarge(response);
        }

        // The header is the recommended way; /proxy/k/{key}/… is for clients that can only change the base URL.
        String headerKey = request.getHeaders().getFirst(ProxyHeaders.PROJECT_KEY);
        String key = headerKey != null && !headerKey.isBlank() ? headerKey : route.get().pathKey();
        return projectKeys.resolve(key)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .onErrorMap(KeyCheckUnavailable::new)
                .flatMap(projectId -> {
                    if (projectId.isEmpty() && props.requireProjectKey()) {
                        return ProxyErrors.write(response, HttpStatus.UNAUTHORIZED, "invalid_project_key",
                                "Missing or unknown project key (X-Tracker-Key header or /proxy/k/{key}/ path)");
                    }
                    return readBody(request).flatMap(body -> forward(exchange, provider.get(), route.get(),
                            target.get(), projectId.orElse(null), body, startNanos));
                })
                .onErrorResume(KeyCheckUnavailable.class, e -> ProxyErrors.write(response,
                        HttpStatus.SERVICE_UNAVAILABLE, "tracker_unavailable",
                        "Project key could not be checked; try again shortly"))
                .onErrorResume(DataBufferLimitException.class, e -> tooLarge(response));
    }

    private Mono<Void> tooLarge(ServerHttpResponse response) {
        return ProxyErrors.write(response, HttpStatus.PAYLOAD_TOO_LARGE, "request_too_large",
                "Request body exceeds " + props.maxRequestBody());
    }

    private Mono<byte[]> readBody(ServerHttpRequest request) {
        return DataBufferUtils.join(request.getBody(), (int) Math.min(Integer.MAX_VALUE, props.maxRequestBody().toBytes()))
                .map(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return bytes;
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .defaultIfEmpty(EMPTY);
    }

    private Mono<Void> forward(ServerWebExchange exchange, Provider provider, ProxyRoute route, URI target,
            UUID projectId, byte[] body, long startNanos) {
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();

        Recording recording = null;
        byte[] outgoing = body;
        if (projectId != null) {
            Optional<Endpoint> endpoint = Endpoint.classify(provider.dialect(), request.getMethod(), route.upstreamPath());
            if (endpoint.isPresent()) {
                Endpoint ep = endpoint.get();
                RequestInfo info = RequestInfo.of(body);
                // Streamed Chat Completions only report usage with stream_options.include_usage. Add it when the
                // client did not set stream_options at all (only that field changes); if the client set it, even
                // to include_usage=false, the request is forwarded untouched.
                if (info.stream() && ep == Endpoint.CHAT_COMPLETIONS && provider.dialect() == Dialect.OPENAI
                        && props.openaiInjectStreamUsage() && info.root() != null
                        && !info.root().has("stream_options")) {
                    outgoing = StreamOptions.injectIncludeUsage(body);
                }
                // The fingerprint is computed only if no header or path agent matched; the prompt text never
                // leaves Fingerprinter, and only the hash is kept.
                RequestAttribution attribution = RequestAttribution.resolve(request.getHeaders(),
                        route.agentName(),
                        () -> fingerprinter == null ? null : fingerprinter.fingerprint(ep, info.root()).orElse(null));
                recording = new Recording(projectId, provider.key(), ep, info.model(), attribution, startNanos);
            }
        }
        boolean parseResponse = recording != null;
        byte[] sent = outgoing;

        WebClient.RequestBodySpec spec = upstream.method(request.getMethod())
                .uri(target)
                .headers(h -> {
                    ProxyHeaders.copyRequestHeaders(request.getHeaders(), h, parseResponse);
                    if (sent.length > 0) {
                        h.setContentLength(sent.length);
                    }
                });
        WebClient.RequestHeadersSpec<?> call = sent.length == 0
                ? spec
                : spec.body(BodyInserters.fromDataBuffers(Mono.fromSupplier(() -> response.bufferFactory().wrap(sent))));

        Recording rec = recording;
        return call.exchangeToMono(upstreamResponse -> relay(response, upstreamResponse, rec))
                .onErrorResume(e -> {
                    if (response.isCommitted()) {
                        return Mono.error(e);
                    }
                    boolean timeout = hasCause(e, ReadTimeoutException.class)
                            || hasCause(e, java.util.concurrent.TimeoutException.class);
                    return ProxyErrors.write(response,
                            timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY,
                            timeout ? "upstream_timeout" : "upstream_unavailable",
                            timeout ? "Upstream did not respond in time" : "Could not reach upstream provider");
                });
    }

    private Mono<Void> relay(ServerHttpResponse response, ClientResponse upstreamResponse, Recording rec) {
        response.setStatusCode(upstreamResponse.statusCode());
        ProxyHeaders.copyResponseHeaders(upstreamResponse.headers().asHttpHeaders(), response.getHeaders());
        Flux<DataBuffer> body = upstreamResponse.bodyToFlux(DataBuffer.class);

        boolean eventStream = upstreamResponse.headers().contentType()
                .map(MediaType.TEXT_EVENT_STREAM::isCompatibleWith)
                .orElse(false);
        int status = upstreamResponse.statusCode().value();

        if (eventStream) {
            // Each chunk is written and flushed as it arrives; nothing is buffered. A copy of each chunk feeds a
            // tracker that keeps only a bounded window. If the client disconnects, cancelling this write cancels
            // the upstream body, which closes the upstream connection so generation (and billing) stops.
            StreamUsageTracker tracker = rec == null ? null : new StreamUsageTracker(rec.endpoint);
            Flux<DataBuffer> chunks = tracker == null
                    ? body
                    : body.doOnNext(buffer -> {
                        try (DataBuffer.ByteBufferIterator it = buffer.readableByteBuffers()) {
                            while (it.hasNext()) {
                                tracker.feed(it.next());
                            }
                        }
                    });
            Mono<Void> write = response.writeAndFlushWith(chunks.map(Mono::just));
            return tracker == null
                    ? write
                    : write.doFinally(signal -> rec.finishStream(status, tracker, signal != SignalType.ON_COMPLETE));
        }
        if (rec == null) {
            return response.writeWith(body);
        }
        BodyCopy copy = new BodyCopy(props.maxRequestBody().toBytes());
        return response.writeWith(body.doOnNext(copy::append))
                .doFinally(signal -> rec.finish(status, copy, signal != SignalType.ON_COMPLETE));
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * What the proxy reads from a recorded request body: the model (fallback), the stream flag, and the parsed tree
     * for fingerprinting. The tree is dropped as soon as {@link #forward} returns; nothing from it is stored.
     */
    record RequestInfo(String model, boolean stream, JsonNode root) {
        static RequestInfo of(byte[] body) {
            try {
                JsonNode root = JSON.readTree(body);
                if (root == null || !root.isObject()) {
                    return new RequestInfo(null, false, null);
                }
                String model = root.path("model").isTextual() ? root.path("model").asText() : null;
                return new RequestInfo(model, root.path("stream").asBoolean(false), root);
            } catch (Exception e) {
                return new RequestInfo(null, false, null);
            }
        }

        @Override
        public String toString() {
            return "RequestInfo[model=" + model + ", stream=" + stream + "]"; // never print the body
        }
    }

    /** Builds the usage event once the response has been fully relayed (or the client went away). */
    private final class Recording {
        private final UUID projectId;
        private final String provider;
        private final Endpoint endpoint;
        private final String requestModel;
        private final RequestAttribution attribution;
        private final long startNanos;

        Recording(UUID projectId, String provider, Endpoint endpoint, String requestModel,
                RequestAttribution attribution, long startNanos) {
            this.projectId = projectId;
            this.provider = provider;
            this.endpoint = endpoint;
            this.requestModel = requestModel;
            this.attribution = attribution;
            this.startNanos = startNanos;
        }

        /** Non-streaming response: usage parsed from the side copy of the body. */
        void finish(int status, BodyCopy copy, boolean incomplete) {
            record(status, () -> parse(copy), false, incomplete);
        }

        /**
         * Streamed response: usage seen so far. Incomplete if the stream did not end normally (client disconnect,
         * upstream dropped, error event, or no final usage, e.g. Chat Completions with include_usage=false).
         */
        void finishStream(int status, StreamUsageTracker tracker, boolean abnormalEnd) {
            record(status, tracker::usage, true, abnormalEnd || !tracker.completedNormally());
        }

        private void record(int status, java.util.function.Supplier<Usage> usageSupplier, boolean streamed,
                boolean incomplete) {
            try {
                int latencyMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startNanos) / 1_000_000);
                Usage usage = usageSupplier.get();
                String model = usage.model() != null ? usage.model() : requestModel;
                queue.offer(new UsageEvent(projectId, attribution.agent(), attribution.runId(), attribution.tags(),
                        provider, endpoint.wireName(), model, usage, streamed, incomplete, status, latencyMs,
                        Instant.now()));
            } catch (RuntimeException e) {
                // Never let recording affect the client; never log content.
                log.warn("Could not record usage event ({})", e.getClass().getSimpleName());
            }
        }

        private Usage parse(BodyCopy copy) {
            if (copy.overflowed()) {
                return Usage.NONE;
            }
            try {
                JsonNode root = JSON.readTree(copy.bytes());
                return switch (endpoint) {
                    case CHAT_COMPLETIONS -> OpenAiChatUsageParser.parse(root);
                    case RESPONSES -> OpenAiResponsesUsageParser.parse(root);
                    case MESSAGES -> AnthropicMessagesUsageParser.parse(root);
                };
            } catch (Exception e) {
                // Body was not JSON (e.g. an HTML error page). Record the status with zero usage.
                log.debug("Response body not parseable as JSON ({})", e.getClass().getSimpleName());
                return Usage.NONE;
            }
        }
    }
}
