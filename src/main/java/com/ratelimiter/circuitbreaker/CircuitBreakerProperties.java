package com.ratelimiter.circuitbreaker;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the Circuit Breaker.
 * Bound from the {@code rate-limiter.circuit-breaker} namespace in
 * application.yml.
 */
@Data
@Component
@ConfigurationProperties(prefix = "rate-limiter.circuit-breaker")
public class CircuitBreakerProperties {

    /**
     * Whether the circuit breaker is active at all.
     * Set to false to disable all circuit-breaker logic (useful for local dev).
     */
    private boolean enabled = true;

    /**
     * Number of consecutive Redis failures needed to trip the circuit from CLOSED →
     * OPEN.
     * Default: 5
     */
    private int failureThreshold = 5;

    /**
     * Monitoring window in seconds. Failure count resets are not time-windowed in
     * this
     * simple implementation — this field is used as metadata / future enhancement.
     */
    private int windowSeconds = 60;

    /**
     * Seconds to wait in OPEN state before transitioning to HALF-OPEN for a probe.
     * This is the BASE timeout — escalation multiplies it for repeated trips.
     */
    private int halfOpenTimeoutSeconds = 30;

    /**
     * Fail-open mode:
     * - true → When OPEN, allow all requests through (availability over
     * consistency).
     * - false → When OPEN, reject all requests with 503 (strict protection).
     */
    private boolean failOpen = true;
}
