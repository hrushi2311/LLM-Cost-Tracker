package dev.llmcosttracker.api;

import java.util.concurrent.Callable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Blocking JDBC runs only on {@code Schedulers.boundedElastic()}, never on a Netty event-loop thread. */
public final class Blocking {

    private Blocking() {}

    public static <T> Mono<T> call(Callable<T> work) {
        return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());
    }

    public static Mono<Void> run(Runnable work) {
        return Mono.fromRunnable(work).subscribeOn(Schedulers.boundedElastic()).then();
    }
}
