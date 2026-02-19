package com.ratelimiter.circuitbreaker;

/**
 * Represents the three states of the Circuit Breaker state machine.
 *
 * CLOSED → Normal operation. All requests flow through.
 * Failures are counted. If failures exceed threshold → OPEN.
 *
 * OPEN → Circuit tripped. Redis is considered unreliable.
 * In fail-open mode → all requests are allowed (fallback).
 * In fail-closed mode → all requests are blocked (503).
 * After the open-duration expires → transitions to HALF_OPEN.
 *
 * HALF_OPEN → A single probe request is allowed through to test recovery.
 * If the probe succeeds → CLOSED.
 * If the probe fails → OPEN again (with escalated duration).
 */
public enum CircuitBreakerState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
