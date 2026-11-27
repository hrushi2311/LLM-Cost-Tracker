package dev.llmcosttracker.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.netty.NettyServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.netty.http.server.logging.AccessLog;

/**
 * Optional HTTP access log ({@code TRACKER_ACCESS_LOG=true}), written by Reactor Netty to the
 * {@code reactor.netty.http.server.AccessLog} logger. The URI is redacted before formatting, so a project key in
 * {@code /proxy/k/{key}/…} never reaches the log; the log converter would mask it again as a safety net.
 * Headers are never part of the access log.
 */
@Configuration
@ConditionalOnProperty(prefix = "tracker", name = "access-log", havingValue = "true")
class AccessLogConfig {

    @Bean
    NettyServerCustomizer redactedAccessLog() {
        return server -> server.accessLog(true, args -> AccessLog.create(
                "{} \"{} {} {}\" {} {} {} ms",
                args.remoteAddress() == null ? "-" : String.valueOf(args.remoteAddress()),
                args.method(),
                RedactingMessageConverter.redactPathKey(String.valueOf(args.uri())),
                args.protocol(),
                args.status(),
                args.contentLength() < 0 ? "-" : args.contentLength(),
                args.duration()));
    }
}
