package dev.llmcosttracker.config;

import ch.qos.logback.classic.spi.IThrowableProxy;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;

/**
 * Logback converter for {@code %wEx} (Spring Boot's exception output): exception messages can carry a request URI,
 * so stack traces go through the same redaction as log messages.
 */
public class RedactingThrowableConverter extends ExtendedWhitespaceThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy tp) {
        return RedactingMessageConverter.redact(super.throwableProxyToString(tp));
    }
}
