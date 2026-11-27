package dev.llmcosttracker.api;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.MethodNotAllowedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * Turns every failure under {@code /api} into the one error shape. Runs before Spring Boot's default handler
 * (order -1) and leaves other paths alone.
 */
@Component
@Order(-2)
public class ApiErrorHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (!exchange.getRequest().getPath().value().startsWith("/api/")) {
            return Mono.error(ex);
        }
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex); // e.g. a CSV stream that failed half-way: nothing left to do but close
        }
        return switch (ex) {
            case ApiException e -> ApiErrors.write(exchange.getResponse(), e.status(), e.code(), e.getMessage());
            case WebExchangeBindException e -> ApiErrors.write(exchange.getResponse(), HttpStatus.BAD_REQUEST,
                    "validation_error", e.getFieldErrors().stream()
                            .map(f -> f.getField() + ": " + f.getDefaultMessage())
                            .collect(Collectors.joining("; ")));
            case UnsupportedMediaTypeStatusException e -> ApiErrors.write(exchange.getResponse(),
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", "Send the body as application/json");
            case MethodNotAllowedException e -> ApiErrors.write(exchange.getResponse(), HttpStatus.METHOD_NOT_ALLOWED,
                    "method_not_allowed", "Method " + e.getHttpMethod() + " is not allowed here");
            case ServerWebInputException e -> ApiErrors.write(exchange.getResponse(), HttpStatus.BAD_REQUEST,
                    "invalid_request", describeInputError(e));
            case ResponseStatusException e when e.getStatusCode().value() == 404 -> ApiErrors.write(
                    exchange.getResponse(), HttpStatus.NOT_FOUND, "not_found", "No such API endpoint");
            case ResponseStatusException e -> ApiErrors.write(exchange.getResponse(),
                    HttpStatus.valueOf(e.getStatusCode().value()), "error", "Request failed");
            default -> {
                log.error("Unhandled error in dashboard API", ex);
                yield ApiErrors.write(exchange.getResponse(), HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                        "Internal error");
            }
        };
    }

    /** A message naming the offending field, without echoing the value that was sent. */
    static String describeInputError(ServerWebInputException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnrecognizedPropertyException u) {
                return "Unknown field '" + u.getPropertyName() + "'";
            }
            if (t instanceof JsonMappingException m && !m.getPath().isEmpty()) {
                String path = m.getPath().stream()
                        .map(r -> r.getFieldName() != null ? r.getFieldName() : "[" + r.getIndex() + "]")
                        .collect(Collectors.joining("."));
                return "Invalid value for '" + path + "'";
            }
            if (t instanceof com.fasterxml.jackson.core.JsonProcessingException) {
                return "Request body is not valid JSON";
            }
        }
        if (e.getMethodParameter() != null && e.getMethodParameter().getParameterName() != null) {
            return "Invalid value for parameter '" + e.getMethodParameter().getParameterName() + "'";
        }
        String reason = e.getReason();
        if (reason != null && reason.contains("body")) {
            return "Request body is required";
        }
        return "Invalid request";
    }
}
