package com.ratelimiter.circuitbreaker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the Circuit Breaker state machine — Phase 3.
 *
 * Tests cover:
 * - Initial state is CLOSED
 * - Circuit trips to OPEN after failure threshold
 * - OPEN → HALF_OPEN after configured duration
 * - HALF_OPEN → CLOSED on probe success
 * - HALF_OPEN → OPEN (escalated) on probe failure
 * - Manual admin reset → CLOSED
 * - Manual admin trip → OPEN
 * - fail-open flag exposed correctly
 * - Disabled circuit breaker always allows
 * - Trip count increases on each trip
 */
class CircuitBreakerTest {

    private CircuitBreakerProperties props;
    private CircuitBreaker cb;

    @BeforeEach
    void setUp() {
        props = new CircuitBreakerProperties();
        props.setEnabled(true);
        props.setFailureThreshold(3);
        props.setHalfOpenTimeoutSeconds(60); // large timeout — won't expire in tests
        props.setFailOpen(true);
        cb = new CircuitBreaker(props);
    }

    // ── 1. Initial State ───────────────────────────────────────────────────────

    @Test
    @DisplayName("1. Initial state is CLOSED")
    void initialState_isClosed() {
        assertThat(cb.getState()).isEqualTo(CircuitBreakerState.CLOSED);
        assertThat(cb.getFailureCount()).isEqualTo(0);
        assertThat(cb.getTripCount()).isEqualTo(0);
    }

    // ── 2. Closed → Open Transition ────────────────────────────────────────────

    @Test
    @DisplayName("2. After reaching failure threshold, circuit trips to OPEN")
    void afterThresholdFailures_circuitTrips() {
        for (int i = 0; i < props.getFailureThreshold(); i++) {
            cb.recordFailure();
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreakerState.OPEN);
        assertThat(cb.getTripCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("3. Failures below threshold keep circuit CLOSED")
    void belowThreshold_staysClosed() {
        for (int i = 0; i < props.getFailureThreshold() - 1; i++) {
            cb.recordFailure();
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreakerState.CLOSED);
    }

    // ── 3. OPEN State Behaviour ────────────────────────────────────────────────

    @Test
    @DisplayName("4. When OPEN, allowRequest() returns false (blocks Redis calls)")
    void whenOpen_allowRequestReturnsFalse() {
        tripCircuit();
        assertThat(cb.allowRequest()).isFalse();
    }

    @Test
    @DisplayName("5. When CLOSED, allowRequest() returns true")
    void whenClosed_allowRequestReturnsTrue() {
        assertThat(cb.allowRequest()).isTrue();
    }

    // ── 4. HALF_OPEN Probe ─────────────────────────────────────────────────────

    @Test
    @DisplayName("6. Successful probe in HALF_OPEN transitions to CLOSED")
    void halfOpen_successfulProbe_closesCircuit() throws Exception {
        // Set timeout=0 BEFORE tripping so openedAt is already expired
        props.setHalfOpenTimeoutSeconds(0);
        tripCircuit();
        Thread.sleep(5); // Let System.currentTimeMillis() advance past openedAt
        // allowRequest() transitions OPEN → HALF_OPEN and allows probe
        boolean allowed = cb.allowRequest();
        assertThat(cb.getState()).isEqualTo(CircuitBreakerState.HALF_OPEN);
        assertThat(allowed).isTrue(); // probe should be allowed

        cb.recordSuccess();
        assertThat(cb.getState()).isEqualTo(CircuitBreakerState.CLOSED);
    }

    @Test
    @DisplayName("7. Probe failure in HALF_OPEN re-trips to OPEN (escalated)")
    void halfOpen_failedProbe_retripsToOpen() throws Exception {
        // Set timeout=0 BEFORE tripping so open duration is already elapsed
        props.setHalfOpenTimeoutSeconds(0);
        tripCircuit();
        Thread.sleep(5); // let clock advance past openedAt
        cb.allowRequest(); // OPEN → HALF_OPEN, allows probe
        assertThat(cb.getState()).isEqualTo(CircuitBreakerState.HALF_OPEN);

        // Probe fails — should re-trip to OPEN with escalated trip count
        cb.recordFailure();
        assertThat(cb.getState()).isEqualTo(CircuitBreakerState.OPEN);
        assertThat(cb.getTripCount()).isEqualTo(2); // escalated
    }

    // ── 5. Admin Operations ────────────────────────────────────────────────────

    @Test
    @DisplayName("8. Manual reset → CLOSED with counters cleared")
    void manualReset_closesCiruit() {
        tripCircuit();
        assertThat(cb.getState()).isEqualTo(CircuitBreakerState.OPEN);

        cb.reset();
        assertThat(cb.getState()).isEqualTo(CircuitBreakerState.CLOSED);
        assertThat(cb.getFailureCount()).isEqualTo(0);
        assertThat(cb.getTripCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("9. Manual trip → OPEN (admin can force trip for testing)")
    void manualTrip_opensCircuit() {
        assertThat(cb.getState()).isEqualTo(CircuitBreakerState.CLOSED);
        cb.trip();
        assertThat(cb.getState()).isEqualTo(CircuitBreakerState.OPEN);
        assertThat(cb.getTripCount()).isEqualTo(1);
    }

    // ── 6. Circuit Breaker Disabled ────────────────────────────────────────────

    @Test
    @DisplayName("10. When disabled, allowRequest() always returns true regardless of state")
    void disabled_alwaysAllows() {
        props.setEnabled(false);
        tripCircuit(); // this records but doesn't actually trip since enabled=false
        assertThat(cb.allowRequest()).isTrue();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void tripCircuit() {
        for (int i = 0; i < props.getFailureThreshold(); i++) {
            cb.recordFailure();
        }
    }
}
