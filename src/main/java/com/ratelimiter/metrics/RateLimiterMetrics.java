package com.ratelimiter.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import com.ratelimiter.circuitbreaker.CircuitBreaker;
import org.springframework.stereotype.Component;

/**
 * Prometheus metrics for the Rate Limiter — Phase 3.
 *
 * Metrics exposed at /actuator/prometheus:
 *
 * rate_limiter_requests_total{result="allowed"} — allowed request count
 * rate_limiter_requests_total{result="rejected"} — rejected (429) request count
 * rate_limiter_fallback_activations_total — times in-memory fallback was used
 * rate_limiter_circuit_state — gauge: 0=CLOSED, 1=OPEN, 2=HALF_OPEN
 */
@Component
public class RateLimiterMetrics {

    private final Counter allowedCounter;
    private final Counter rejectedCounter;
    private final Counter fallbackCounter;

    public RateLimiterMetrics(MeterRegistry registry, CircuitBreaker circuitBreaker) {
        this.allowedCounter = Counter.builder("rate_limiter_requests_total")
                .description("Total rate limiter decisions")
                .tag("result", "allowed")
                .register(registry);

        this.rejectedCounter = Counter.builder("rate_limiter_requests_total")
                .description("Total rate limiter decisions")
                .tag("result", "rejected")
                .register(registry);

        this.fallbackCounter = Counter.builder("rate_limiter_fallback_activations_total")
                .description("Times the Redis fallback (in-memory) was activated")
                .register(registry);

        // Gauge: maps CircuitBreakerState → numeric (0=CLOSED, 1=OPEN, 2=HALF_OPEN)
        Gauge.builder("rate_limiter_circuit_state", circuitBreaker, cb -> switch (cb.getState()) {
            case CLOSED -> 0.0;
            case OPEN -> 1.0;
            case HALF_OPEN -> 2.0;
        })
                .description("Circuit breaker state: 0=CLOSED, 1=OPEN, 2=HALF_OPEN")
                .register(registry);
    }

    /** Call when a request is allowed through by the rate limiter. */
    public void recordAllowed() {
        allowedCounter.increment();
    }

    /** Call when a request is rejected with HTTP 429. */
    public void recordRejected() {
        rejectedCounter.increment();
    }

    /** Call when the Redis strategy falls back to in-memory. */
    public void recordFallback() {
        fallbackCounter.increment();
    }
}
