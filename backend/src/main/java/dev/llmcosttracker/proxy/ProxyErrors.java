package dev.llmcosttracker.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import reactor.core.publisher.Mono;

/** Errors the proxy itself produces, in the shape {@code {"error":{"code":"…","message":"…"}}}. */
final class ProxyErrors {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ProxyErrors() {}

    static Mono<Void> write(ServerHttpResponse response, HttpStatus status, String code, String message) {
        if (response.isCommitted()) {
            return Mono.empty();
        }
        ObjectNode root = JSON.createObjectNode();
        ObjectNode error = root.putObject("error");
        error.put("code", code);
        error.put("message", message);
        byte[] body;
        try {
            body = JSON.writeValueAsBytes(root);
        } catch (Exception e) {
            body = ("{\"error\":{\"code\":\"" + code + "\"}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().setContentLength(body.length);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}
