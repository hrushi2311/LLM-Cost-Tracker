package dev.llmcosttracker.proxy;

import dev.llmcosttracker.config.TrackerProperties;
import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
class UpstreamClientConfig {

    /**
     * Dedicated client for upstream calls: no compression (bytes relayed as received), no redirects followed,
     * no request/response logging.
     */
    @Bean
    WebClient upstreamWebClient(TrackerProperties props) {
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) props.connectTimeout().toMillis())
                .responseTimeout(props.upstreamTimeout())
                .followRedirect(false)
                .compress(false);
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(http)).build();
    }
}
