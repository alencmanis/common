package com.example.common.fly;

import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

public final class RetryUtil {
    private RetryUtil() {}

    public static Retry flyRetry() {
        // English comment: Backoff for rate limits / transient failures.
        return Retry.backoff(6, Duration.ofMillis(300))
                .maxBackoff(Duration.ofSeconds(5))
                .filter(e -> {
                    if (e instanceof FlyApiException fae) {
                        int s = fae.status();
                        return s == 429 || (s >= 500 && s <= 599);
                    }
                    return false;
                });
    }

    public static <T> Mono<T> withFlyRetry(Mono<T> mono) {
        return mono.retryWhen(flyRetry());
    }
}
