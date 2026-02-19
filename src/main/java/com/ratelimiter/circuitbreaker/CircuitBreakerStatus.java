package com.ratelimiter.circuitbreaker;

import lombok.Builder;
import lombok.Getter;

/**
 * Snapshot DTO returned by the circuit breaker status admin endpoint.
 */
@Getter
@Builder
public class CircuitBreakerStatus {
    private final CircuitBreakerState state;
    private final int failureCount;
    private final int tripCount;
    private final String openedAt; // ISO-8601 string, null if never tripped
    private final long openDurationSeconds; // Current open-duration based on trip count
    private final boolean failOpen;
    private final boolean enabled;
}
