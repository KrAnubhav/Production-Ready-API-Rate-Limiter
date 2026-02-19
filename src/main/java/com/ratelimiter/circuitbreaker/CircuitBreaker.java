package com.ratelimiter.circuitbreaker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Circuit Breaker for the Redis Rate Limiter — Phase 3.
 *
 * State machine:
 *
 * CLOSED ──[failures > threshold]──► OPEN
 * ▲ │
 * │ [open duration elapsed]
 * │ ▼
 * └──[probe success]────── HALF_OPEN
 * │
 * [probe failure] → OPEN (escalated)
 *
 * Escalating OPEN durations:
 * 1st trip → 5 minutes
 * 2nd trip → 15 minutes
 * 3rd trip+ → 1 hour
 *
 * Thread safety:
 * State transitions are guarded by a ReentrantLock.
 * Counters use AtomicInteger for lock-free increment/read.
 */
@Slf4j
@Component
public class CircuitBreaker {

    // ── State ──────────────────────────────────────────────────────────────────

    private volatile CircuitBreakerState state = CircuitBreakerState.CLOSED;
    private final ReentrantLock stateLock = new ReentrantLock();

    /** Cumulative count of Redis failures since last reset. */
    private final AtomicInteger failureCount = new AtomicInteger(0);

    /** How many times the circuit has tripped (for escalation). */
    private final AtomicInteger tripCount = new AtomicInteger(0);

    /** Epoch-ms when the circuit last transitioned to OPEN. */
    private volatile long openedAt = 0L;

    /** Whether a probe request has already been let through in HALF_OPEN. */
    private volatile boolean probeInFlight = false;

    // ── Config ─────────────────────────────────────────────────────────────────

    private final CircuitBreakerProperties props;

    public CircuitBreaker(CircuitBreakerProperties props) {
        this.props = props;
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Check whether the circuit breaker allows this request to proceed to Redis.
     *
     * @return true → proceed to Redis
     *         false → Redis is unavailable; caller should apply fail-open or
     *         fail-closed policy
     */
    public boolean allowRequest() {
        if (!props.isEnabled())
            return true; // circuit breaker disabled

        CircuitBreakerState current = state;

        switch (current) {
            case CLOSED:
                return true;

            case OPEN:
                // Transition to HALF_OPEN if open duration has elapsed
                if (openDurationElapsed()) {
                    transitionTo(CircuitBreakerState.HALF_OPEN);
                    return allowProbe();
                }
                return false; // Still OPEN → deny Redis call

            case HALF_OPEN:
                return allowProbe();

            default:
                return true;
        }
    }

    /**
     * Record a successful Redis call.
     * In HALF_OPEN: closes the circuit.
     * In CLOSED: resets failure counter.
     */
    public void recordSuccess() {
        if (!props.isEnabled())
            return;

        stateLock.lock();
        try {
            if (state == CircuitBreakerState.HALF_OPEN) {
                log.info("[CircuitBreaker] Probe succeeded — transitioning HALF_OPEN → CLOSED");
                failureCount.set(0);
                probeInFlight = false;
                state = CircuitBreakerState.CLOSED;
            } else if (state == CircuitBreakerState.CLOSED) {
                // Partial success — reset failure count on clean operations
                failureCount.set(0);
            }
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Record a failed Redis call.
     * Increments failure counter; trips to OPEN when threshold is exceeded.
     */
    public void recordFailure() {
        if (!props.isEnabled())
            return;

        int failures = failureCount.incrementAndGet();
        log.warn("[CircuitBreaker] Redis failure recorded. Count={} / Threshold={}",
                failures, props.getFailureThreshold());

        if (failures >= props.getFailureThreshold()) {
            stateLock.lock();
            try {
                if (state == CircuitBreakerState.CLOSED || state == CircuitBreakerState.HALF_OPEN) {
                    tripCircuit();
                }
            } finally {
                stateLock.unlock();
            }
        }
    }

    /** Manually force the circuit to CLOSED (admin reset). */
    public void reset() {
        stateLock.lock();
        try {
            log.info("[CircuitBreaker] Manual reset → CLOSED");
            failureCount.set(0);
            probeInFlight = false;
            tripCount.set(0);
            state = CircuitBreakerState.CLOSED;
        } finally {
            stateLock.unlock();
        }
    }

    /** Manually force circuit OPEN (admin trip, for testing). */
    public void trip() {
        stateLock.lock();
        try {
            log.info("[CircuitBreaker] Manual trip → OPEN");
            tripCircuit();
        } finally {
            stateLock.unlock();
        }
    }

    // ── Getters ────────────────────────────────────────────────────────────────

    public CircuitBreakerState getState() {
        return state;
    }

    public int getFailureCount() {
        return failureCount.get();
    }

    public int getTripCount() {
        return tripCount.get();
    }

    public long getOpenedAt() {
        return openedAt;
    }

    public boolean isFailOpen() {
        return props.isFailOpen();
    }

    public long getOpenDurationMillis() {
        return computeOpenDurationMillis();
    }

    /**
     * Returns a snapshot DTO for the admin status endpoint.
     */
    public CircuitBreakerStatus getStatus() {
        return CircuitBreakerStatus.builder()
                .state(state)
                .failureCount(failureCount.get())
                .tripCount(tripCount.get())
                .openedAt(openedAt > 0 ? Instant.ofEpochMilli(openedAt).toString() : null)
                .openDurationSeconds(computeOpenDurationMillis() / 1000)
                .failOpen(props.isFailOpen())
                .enabled(props.isEnabled())
                .build();
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    /** Must be called inside stateLock. */
    private void tripCircuit() {
        int trips = tripCount.incrementAndGet();
        openedAt = System.currentTimeMillis();
        probeInFlight = false;
        state = CircuitBreakerState.OPEN;
        log.warn("[CircuitBreaker] Circuit TRIPPED → OPEN. Trip #{}, open for {}s",
                trips, computeOpenDurationMillis() / 1000);
    }

    private void transitionTo(CircuitBreakerState next) {
        stateLock.lock();
        try {
            if (state != next) {
                log.info("[CircuitBreaker] {} → {}", state, next);
                state = next;
            }
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Allow one probe through in HALF_OPEN state.
     * Uses probeInFlight flag to ensure only ONE probe at a time.
     */
    private boolean allowProbe() {
        stateLock.lock();
        try {
            if (!probeInFlight) {
                probeInFlight = true;
                log.info("[CircuitBreaker] HALF_OPEN — allowing probe request");
                return true;
            }
            // Another probe is already in-flight → deny this one
            return false;
        } finally {
            stateLock.unlock();
        }
    }

    private boolean openDurationElapsed() {
        return System.currentTimeMillis() - openedAt >= computeOpenDurationMillis();
    }

    /**
     * Escalating OPEN durations based on trip count and configured base timeout:
     * Trip 1 → base
     * Trip 2 → base × 3
     * Trip 3+ → base × 12
     *
     * Default (halfOpenTimeoutSeconds=30):
     * Trip 1 → 30s, Trip 2 → 90s, Trip 3+ → 360s
     */
    private long computeOpenDurationMillis() {
        long base = props.getHalfOpenTimeoutSeconds() * 1000L;
        return switch (tripCount.get()) {
            case 0, 1 -> base;
            case 2 -> base * 3;
            default -> base * 12;
        };
    }
}
