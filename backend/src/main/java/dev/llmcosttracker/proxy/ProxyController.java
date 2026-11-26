package dev.llmcosttracker.proxy;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Every method on {@code /proxy/**}. The handler writes the response itself. */
@RestController
class ProxyController {

    private final ProxyService proxy;

    ProxyController(ProxyService proxy) {
        this.proxy = proxy;
    }

    @RequestMapping("/proxy/**")
    Mono<Void> proxy(ServerWebExchange exchange) {
        return proxy.handle(exchange);
    }
}
