# Phase 3 Walkthrough — Circuit Breaker

## What Was Built

Phase 3 added a **Circuit Breaker pattern** to the Redis Rate Limiter for resiliency when Redis becomes unavailable.

---

## New Files Created

| File | Purpose |
|------|---------|
| [circuitbreaker/CircuitBreakerState.java](file:///Users/anubhavgarg/Downloads/API-Rate-Limiter/src/main/java/com/ratelimiter/circuitbreaker/CircuitBreakerState.java) | Enum: CLOSED, OPEN, HALF_OPEN |
| [circuitbreaker/CircuitBreakerProperties.java](file:///Users/anubhavgarg/Downloads/API-Rate-Limiter/src/main/java/com/ratelimiter/circuitbreaker/CircuitBreakerProperties.java) | `@ConfigurationProperties` bound to `rate-limiter.circuit-breaker.*` |
| [circuitbreaker/CircuitBreaker.java](file:///Users/anubhavgarg/Downloads/API-Rate-Limiter/src/main/java/com/ratelimiter/circuitbreaker/CircuitBreaker.java) | State machine with `ReentrantLock` + `AtomicInteger` |
| [circuitbreaker/CircuitBreakerStatus.java](file:///Users/anubhavgarg/Downloads/API-Rate-Limiter/src/main/java/com/ratelimiter/circuitbreaker/CircuitBreakerStatus.java) | DTO returned by admin status endpoint |
| [health/RateLimiterHealthIndicator.java](file:///Users/anubhavgarg/Downloads/API-Rate-Limiter/src/main/java/com/ratelimiter/health/RateLimiterHealthIndicator.java) | Spring Actuator health check exposing circuit state |
| [metrics/RateLimiterMetrics.java](file:///Users/anubhavgarg/Downloads/API-Rate-Limiter/src/main/java/com/ratelimiter/metrics/RateLimiterMetrics.java) | Micrometer/Prometheus counters and gauges |
| [CircuitBreakerTest.java](file:///Users/anubhavgarg/Downloads/API-Rate-Limiter/src/test/java/com/ratelimiter/circuitbreaker/CircuitBreakerTest.java) | 10 unit tests covering the state machine |

## Modified Files

| File | Change |
|------|--------|
| [RedisTokenBucketStrategy.java](file:///Users/anubhavgarg/Downloads/API-Rate-Limiter/src/main/java/com/ratelimiter/service/strategy/RedisTokenBucketStrategy.java) | Circuit breaker wraps every Redis call |
| [RedisSlidingWindowStrategy.java](file:///Users/anubhavgarg/Downloads/API-Rate-Limiter/src/main/java/com/ratelimiter/service/strategy/RedisSlidingWindowStrategy.java) | Circuit breaker wraps every Redis call |
| [AdminController.java](file:///Users/anubhavgarg/Downloads/API-Rate-Limiter/src/main/java/com/ratelimiter/controller/AdminController.java) | 3 new CB endpoints added |
| [RateLimiterService.java](file:///Users/anubhavgarg/Downloads/API-Rate-Limiter/src/main/java/com/ratelimiter/service/RateLimiterService.java) | [getActiveAlgorithmName()](file:///Users/anubhavgarg/Downloads/API-Rate-Limiter/src/main/java/com/ratelimiter/service/RateLimiterService.java#105-112) method added |
| [pom.xml](file:///Users/anubhavgarg/Downloads/API-Rate-Limiter/pom.xml) | `micrometer-registry-prometheus` added |
| [application.yml](file:///Users/anubhavgarg/Downloads/API-Rate-Limiter/src/main/resources/application.yml) | `circuit-breaker` config + `prometheus` endpoint |
| [application-local.yml](file:///Users/anubhavgarg/Downloads/API-Rate-Limiter/src/main/resources/application-local.yml) | Local CB overrides (low threshold for demo) |

---

## Circuit Breaker State Machine

```
CLOSED ──[failures >= threshold]─────► OPEN
  ▲                                       │
  │                          [duration elapsed]
  │                                       ▼
  └──[probe success]────────── HALF_OPEN ──[probe failure]──► OPEN (escalated)
```

**Escalating durations** (configurable base via `half-open-timeout-seconds`):
- Trip 1 → base × 1
- Trip 2 → base × 3  
- Trip 3+ → base × 12

Default: 30s base → trips last 30s → 90s → 360s

---

## New Admin Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/admin/circuit-breaker/status` | State, trip count, failure count, fail-open mode |
| POST | `/admin/circuit-breaker/reset` | Force reset to CLOSED |
| POST | `/admin/circuit-breaker/trip` | Force trip to OPEN (for testing) |

## New Prometheus Metrics (at `/actuator/prometheus`)

| Metric | Type | Description |
|--------|------|-------------|
| `rate_limiter_requests_total{result="allowed"}` | Counter | Allowed requests |
| `rate_limiter_requests_total{result="rejected"}` | Counter | Rejected (429) requests |
| `rate_limiter_fallback_activations_total` | Counter | CB fallback activations |
| `rate_limiter_circuit_state` | Gauge | 0=CLOSED, 1=OPEN, 2=HALF_OPEN |

## Health Endpoint

`GET /actuator/health` now includes rate limiter circuit state:

```json
{
  "components": {
    "rateLimiter": {
      "status": "UP",
      "details": {
        "circuit": "CLOSED",
        "algorithm": "REDIS_TOKEN_BUCKET",
        "failureCount": 0
      }
    }
  }
}
```

---

## Test Results

```
Tests run: 44, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

| Test Class | Tests | Result |
|-----------|-------|--------|
| CircuitBreakerTest | 10 | ✅ All pass |
| RedisTokenBucketStrategyTest | 7 | ✅ All pass |
| RedisSlidingWindowStrategyTest | 7 | ✅ All pass |
| RateLimiterIntegrationTest | 7 | ✅ All pass |
| TokenBucketStrategyTest | 7 | ✅ All pass |
| SlidingWindowStrategyTest | 6 | ✅ All pass |

---

## Key Design Decisions

1. **No external library** — Circuit Breaker implemented from scratch to demonstrate understanding
2. **`ReentrantLock` for state transitions** — allows non-blocking reads in [allowRequest()](file:///Users/anubhavgarg/Downloads/API-Rate-Limiter/src/main/java/com/ratelimiter/circuitbreaker/CircuitBreaker.java#63-95) (volatile `state` variable), only locks for writes
3. **Single probe in HALF_OPEN** — `probeInFlight` flag ensures only one request tests recovery
4. **Fail-open default** — prioritizes availability (`fail-open: true`) — traffic served via in-memory fallback when circuit is OPEN
5. **Props-based escalation** — `halfOpenTimeoutSeconds` as configurable base makes testing easy (set to 0 for instant HALF_OPEN)
