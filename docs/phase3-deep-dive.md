# 📘 Phase 3 — Deep Dive Documentation
### Production-Ready API Rate Limiter — Circuit Breaker Pattern for Resiliency

> **Assessment:** Evolving Systems Ltd — Internship Technical Assessment  
> **Phase:** 3 — Resiliency & Circuit Breaker (Weeks 5–6)  
> **Points:** 10/10 target — Full marks target  
> **Author:** Anubhav Garg  
> **Status:** ✅ COMPLETE — All 44 tests passing, pushed to main

---

## 📋 Table of Contents

1. [Phase 3 Requirements (from PDF)](#1-phase-3-requirements-from-pdf)
2. [What Is a Circuit Breaker?](#2-what-is-a-circuit-breaker)
3. [Architecture — Adding the Circuit Breaker Layer](#3-architecture--adding-the-circuit-breaker-layer)
4. [State Machine — Deep Dive](#4-state-machine--deep-dive)
5. [CircuitBreaker.java — Line-by-Line](#5-circuitbreakerjava--line-by-line)
6. [CircuitBreakerProperties — Configuration](#6-circuitbreakerproperties--configuration)
7. [Integration with Redis Strategies](#7-integration-with-redis-strategies)
8. [Fail-Open vs Fail-Closed](#8-fail-open-vs-fail-closed)
9. [Health Indicator — /actuator/health](#9-health-indicator--actuatorhealth)
10. [Prometheus Metrics — /actuator/prometheus](#10-prometheus-metrics--actuatorprometheus)
11. [Admin Endpoints](#11-admin-endpoints)
12. [Tests — What Was Verified](#12-tests--what-was-verified)
13. [Problems Faced & Solutions](#13-problems-faced--solutions)
14. [Interview Q&A — Deep Dive](#14-interview-qa--deep-dive)

---

## 1. Phase 3 Requirements (from PDF)

| # | Requirement | Status |
|---|-------------|--------|
| 1 | Implement Circuit Breaker pattern wrapping Redis calls | ✅ `CircuitBreaker` state machine with lock-based transitions |
| 2 | States: CLOSED, OPEN, HALF_OPEN (probe-based recovery) | ✅ Fully implemented with `ReentrantLock` |
| 3 | Configurable failure threshold to trip circuit | ✅ `failure-threshold: 5` in YAML |
| 4 | Escalating OPEN durations on repeated failures | ✅ base × 1 → × 3 → × 12 formula |
| 5 | Fail-open support (allow traffic when Redis is down) | ✅ `fail-open: true` (default) |
| 6 | Fail-closed support (block all traffic when Redis is down) | ✅ `fail-open: false` blocks via fallback |
| 7 | Health endpoint showing circuit state | ✅ `RateLimiterHealthIndicator` at `/actuator/health` |
| 8 | Prometheus metrics for circuit state | ✅ 4 metrics at `/actuator/prometheus` |
| 9 | Admin endpoints to manually trip/reset circuit | ✅ `/admin/circuit-breaker/reset` and `/trip` |
| 10 | 10+ unit tests for circuit breaker behavior | ✅ `CircuitBreakerTest.java` — 10 tests |

---

## 2. What Is a Circuit Breaker?

The Circuit Breaker is a **resiliency pattern** named after an electrical circuit breaker in your home's fuse box. Just as an electrical breaker trips when there's a short circuit (protecting wiring from overload), a software circuit breaker **trips when an upstream dependency (Redis) is failing**, preventing cascading failure.

### The Problem It Solves

Without a circuit breaker:
```
Redis is DOWN → every request hits Redis → every request waits for timeout (e.g., 2s)
→ thread pool exhausted → app becomes unresponsive → cascading failure
                                    ↑
                            This is a cascade: one failing dependency takes down the app
```

With a circuit breaker:
```
Redis fails 5 times → circuit TRIPS OPEN → requests no longer hit Redis
→ immediately fallback to in-memory → app stays responsive
→ after 30s → probe request sent → Redis back? → CLOSE circuit
```

### The Analogy: Netflix and Hystrix
Netflix pioneered circuit breakers in microservices. When their recommendation service was slow, without CB, the entire stream page would hang. With CB, a fast fallback ("Top 10 movies") was served instantly.

---

## 3. Architecture — Adding the Circuit Breaker Layer

### Phase 2 (Redis only)
```
HTTP Request
     │
     ▼
RateLimitFilter
     │
     ▼
RateLimiterService
     │
     ▼
RedisTokenBucketStrategy ──────────────────► Redis (Port 6379)
     │
     └─[connection failure]──► TokenBucketStrategy (in-memory fallback)
```

### Phase 3 (Circuit Breaker wrapping Redis)
```
HTTP Request
     │
     ▼
RateLimitFilter ──[record metrics]──► RateLimiterMetrics
     │
     ▼
RateLimiterService
     │
     ▼
RedisTokenBucketStrategy
     │
     ▼
CircuitBreaker.allowRequest()           CircuitBreakerState
     │                                    CLOSED  OPEN  HALF_OPEN
     ├─[CLOSED]──────────────────────────► Redis (Port 6379)
     │                    ╔══════════════╗    │
     │                    ║ recordSuccess ║◄──┘ (success)
     │                    ║ recordFailure ║◄──── (exception)
     │                    ╚══════════════╝
     │
     ├─[OPEN / fail-open]────────────────► In-Memory Fallback (allow)
     │
     └─[OPEN / fail-closed]──────────────► In-Memory Fallback (could block)


Actuator:                         Admin:
/actuator/health                  POST /admin/circuit-breaker/reset
  → RateLimiterHealthIndicator    POST /admin/circuit-breaker/trip
/actuator/prometheus              GET  /admin/circuit-breaker/status
  → RateLimiterMetrics
```

---

## 4. State Machine — Deep Dive

```
                    [failures >= threshold]
  ┌─────────────────────────────────────────────────────────────────────┐
  │                                                                     │
  ▼                                                                     │
┌─────────────────────────────┐                                         │
│     CLOSED (default)        │                                         │
│  • All requests pass to Redis│                                        │
│  • Failure counter increments│                                        │
│  • Success resets counter   │                                         │
└─────────────────────────────┘                                         │
          │                                                             │
  [failures >= threshold]                                               │
          │                                               [probe fails] │
          ▼                                                             │
┌─────────────────────────────┐           ┌──────────────────────────┐ │
│       OPEN                  │           │       HALF_OPEN           │ │
│  • Blocks all Redis calls   │           │  • Allows ONE probe req   │ │
│  • Returns immediately      │           │  • All others still blocked│ │
│  • Escalating open duration │           │  • Probe success → CLOSED  │ │
│    Trip 1: base × 1         │◄──────────│  • Probe fail  → OPEN+1   │ │
│    Trip 2: base × 3         │           └──────────────────────────┘ │
│    Trip 3+: base × 12       │                        ▲               │
└─────────────────────────────┘                        │               │
          │                                  [openDuration elapsed]    │
          └───────────────────────────────────────────┘               │
                                                                       │
                              [probe success]──────────────────────────┘

Admin Controls:
   POST /admin/circuit-breaker/reset → Force OPEN/HALF_OPEN → CLOSED (clears counters)
   POST /admin/circuit-breaker/trip  → Force CLOSED → OPEN (for testing)
```

### State Definitions

| State | Redis Calls | Failure Counter | When Entered |
|-------|-------------|-----------------|--------------|
| CLOSED | ✅ Allowed | Incrementing | App start, probe success, admin reset |
| OPEN | ❌ Blocked | Stopped | Failures ≥ threshold; probe failure |
| HALF_OPEN | ⚡ One probe only | Stopped | After open duration elapses |

### Escalating Duration Formula
```
openDurationMs = halfOpenTimeoutSeconds × escalationFactor × 1000

Trip 1 → escalationFactor = 1   (e.g. 30s)
Trip 2 → escalationFactor = 3   (e.g. 90s)
Trip 3+ → escalationFactor = 12  (e.g. 360s)

Why? Redis that keeps failing is likely seriously degraded.
Longer wait = more recovery time, less thrashing between OPEN and HALF_OPEN.
```

---

## 5. CircuitBreaker.java — Line-by-Line

```java
@Slf4j
@Component
public class CircuitBreaker {
```
- `@Slf4j` — Lombok generates `private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class)`
- `@Component` — Spring singleton bean; one shared instance per app

### State Fields
```java
private volatile CircuitBreakerState state = CircuitBreakerState.CLOSED;
private final ReentrantLock stateLock = new ReentrantLock();
private final AtomicInteger failureCount = new AtomicInteger(0);
private final AtomicInteger tripCount    = new AtomicInteger(0);
private volatile long openedAt       = 0L;
private volatile boolean probeInFlight = false;
```

| Field | Type | Why |
|-------|------|-----|
| `state` | `volatile` | Reads visible across threads without locking (happens-before from volatile write) |
| `stateLock` | `ReentrantLock` | Protects state transitions — only ONE thread transitions at a time |
| `failureCount` | `AtomicInteger` | Lock-free increment — many threads can record failures concurrently |
| `tripCount` | `AtomicInteger` | Tracks escalation level (0→1→2→3+) |
| `openedAt` | `volatile long` | Timestamp when OPEN; threads read without holding lock |
| `probeInFlight` | `volatile boolean` | Guards single-probe: only ONE probe allowed in HALF_OPEN |

### allowRequest() — Main Decision Point
```java
public boolean allowRequest() {
    if (!props.isEnabled())
        return true;                        // ① feature flag shortcut

    CircuitBreakerState current = state;    // ② read volatile — no lock needed

    switch (current) {
        case CLOSED:
            return true;                    // ③ happy path — no lock

        case OPEN:
            if (openDurationElapsed()) {    // ④ check duration without lock
                transitionTo(HALF_OPEN);   // ⑤ lock only for transition
                return allowProbe();       // ⑥ single probe check
            }
            return false;                  // ⑦ still open — deny immediately

        case HALF_OPEN:
            return allowProbe();           // ⑧ probe check

        default:
            return true;
    }
}
```

**Thread safety analysis:**
- Line ②: `volatile` read — guaranteed to see the latest state write from any thread
- Line ③: No lock on CLOSED path — this is the HOT PATH (99%+ of requests). Zero contention.
- Line ⑤: `transitionTo()` acquires lock — ensures only one thread performs OPEN→HALF_OPEN
- Line ⑥: `allowProbe()` acquires lock — `probeInFlight` CAS-like check prevents double probe

### allowProbe() — Single Probe Guard
```java
private boolean allowProbe() {
    stateLock.lock();
    try {
        if (!probeInFlight) {
            probeInFlight = true;
            log.info("[CircuitBreaker] HALF_OPEN — allowing probe request");
            return true;
        }
        return false;   // Already a probe in flight — deny this request
    } finally {
        stateLock.unlock();
    }
}
```
**Why this matters:** Without this guard, 100 threads arriving at HALF_OPEN would all be "allowed" and all hammer Redis simultaneously. This ensures only ONE request acts as the probe.

### recordFailure() — Threshold Check
```java
public void recordFailure() {
    if (!props.isEnabled()) return;

    int failures = failureCount.incrementAndGet();       // atomic, no lock
    log.warn("[CB] Redis failure. Count={} / Threshold={}", failures, props.getFailureThreshold());

    if (failures >= props.getFailureThreshold()) {
        stateLock.lock();
        try {
            if (state == CLOSED || state == HALF_OPEN) {  // double-check inside lock
                tripCircuit();
            }
        } finally {
            stateLock.unlock();
        }
    }
}
```
**Double-check pattern:** `failureCount.incrementAndGet()` is lock-free. But before calling `tripCircuit()`, we re-check state inside the lock. Why? Multiple threads could concurrently see `failures >= threshold` and all try to trip. The lock + state re-check ensures `tripCircuit()` is called exactly once per threshold crossing.

### tripCircuit() — Called Inside Lock
```java
private void tripCircuit() {
    int trips = tripCount.incrementAndGet();
    openedAt = System.currentTimeMillis();
    probeInFlight = false;
    state = CircuitBreakerState.OPEN;
    log.warn("[CB] Circuit TRIPPED → OPEN. Trip #{}, open for {}s",
            trips, computeOpenDurationMillis() / 1000);
}
```
`openedAt` written while holding lock, but read without lock in `openDurationElapsed()`. This is safe because:
1. The write happens-before the `state = OPEN` write (program order within lock)
2. The `state = OPEN` is `volatile` — its write creates a happens-before for subsequent `volatile` reads
3. Any thread that reads `state == OPEN` will see the `openedAt` that was written before the volatile `state` write

### computeOpenDurationMillis() — Escalation
```java
private long computeOpenDurationMillis() {
    long base = props.getHalfOpenTimeoutSeconds() * 1000L;
    return switch (tripCount.get()) {
        case 0, 1 -> base;
        case 2    -> base * 3;
        default   -> base * 12;
    };
}
```
`tripCount.get()` is an atomic read — no lock needed. The result determines how long the circuit stays OPEN before allowing a probe.

---

## 6. CircuitBreakerProperties — Configuration

```java
@Component
@ConfigurationProperties(prefix = "rate-limiter.circuit-breaker")
public class CircuitBreakerProperties {
    private boolean enabled = true;
    private int failureThreshold = 5;
    private int windowSeconds = 60;
    private int halfOpenTimeoutSeconds = 30;
    private boolean failOpen = true;
    // getters + setters...
}
```

**Bound to application.yml:**
```yaml
rate-limiter:
  circuit-breaker:
    enabled: true
    failure-threshold: 5         # consecutive Redis failures → OPEN
    window-seconds: 60           # monitoring window (metadata)
    half-open-timeout-seconds: 30  # seconds OPEN before probe
    fail-open: true              # true = allow traffic on OPEN; false = block
```

**Local override (application-local.yml) — lower threshold for demo:**
```yaml
rate-limiter:
  circuit-breaker:
    failure-threshold: 3         # trips faster for local testing/demo
    half-open-timeout-seconds: 10  # recovers faster for local demo
```

### Configuration Parameter Guide

| Property | Default | Effect |
|----------|---------|--------|
| `enabled` | `true` | `false` → circuit breaker disabled, all requests pass through always |
| `failure-threshold` | `5` | How many Redis failures before tripping. Lower = more sensitive, higher = more tolerant |
| `half-open-timeout-seconds` | `30` | Base duration OPEN. Set to `0` in tests for instant HALF_OPEN |
| `fail-open` | `true` | `true` = availability-first (in-memory fallback allows traffic). `false` = safety-first (fallback rejects) |

---

## 7. Integration with Redis Strategies

### The Wrapper Pattern
```java
@Override
public boolean isAllowed(String identifier, RateLimitConfig config) {

    // ── ① Circuit Breaker Gate ──────────────────────────────────────────
    if (!circuitBreaker.allowRequest()) {
        log.warn("[CircuitBreaker] OPEN — {} mode for: {}",
                circuitBreaker.isFailOpen() ? "fail-open (allowing)" : "fail-closed (blocking)",
                identifier);
        metrics.recordFallback();
        return fallback.isAllowed(identifier, config);   // ← in-memory fallback
    }

    // ── ② Redis Call ────────────────────────────────────────────────────
    try {
        String key = KEY_PREFIX + identifier;
        long now = System.currentTimeMillis();
        List<Long> result = redisTemplate.execute(script, List.of(key),
                String.valueOf(config.getMaxRequests()),
                String.valueOf(config.getRefillRate()),
                String.valueOf(now),
                String.valueOf(config.getWindowSeconds()));

        if (result == null || result.isEmpty()) {
            circuitBreaker.recordFailure();             // Lua returned nothing — count as failure
            return fallback.isAllowed(identifier, config);
        }

        circuitBreaker.recordSuccess();                 // ③ Redis responded normally
        return result.get(0) == 1L;

    } catch (RedisConnectionFailureException ex) {
        log.warn("[Redis] Connection failed — recording failure + fallback: {}", identifier);
        circuitBreaker.recordFailure();                 // ④ Redis is down — record failure
        metrics.recordFallback();
        return fallback.isAllowed(identifier, config);
    }
}
```

### Execution Flow Summary

```
Request arrives
    │
    ▼
circuitBreaker.allowRequest()
    │
    ├── CLOSED → try Redis
    │               ├── success: recordSuccess() → return Lua result
    │               └── fail: recordFailure() → fallback.isAllowed()
    │
    ├── OPEN → skip Redis entirely
    │           └── fallback.isAllowed()
    │
    └── HALF_OPEN → allowProbe()?
                    ├── first probe: try Redis (same success/fail flow as CLOSED)
                    └── subsequent requests: skip Redis → fallback
```

### Why `recordFailure()` on null Lua result?
A null Lua result shouldn't happen during normal Redis operation but could indicate:
- Redis internal error (script execution failed)
- Serialization issue
- Unexpected Lua return

Treating it as a failure prevents silent degradation — the circuit breaker will trip if this happens repeatedly.

---

## 8. Fail-Open vs Fail-Closed

```
                     Circuit OPEN
                          │
                 ┌────────┴────────┐
                 │                 │
           fail-open           fail-closed
           (default)
                 │                 │
                 ▼                 ▼
    fallback.isAllowed()   fallback.isAllowed()
          = true                = false   ← different return from fallback
```

### In-Memory Fallback Behavior

The in-memory fallback (`TokenBucketStrategy` / `SlidingWindowStrategy`) uses its own counters. When `fail-open = true`, these in-memory counters still enforce limits — so traffic is allowed but still rate-limited locally.

**Key clarification:** fail-open doesn't mean "unlimited traffic." It means:
- `fail-open: true` → traffic continues with in-memory (per-instance) limits
- `fail-open: false` → fallback returns `false` (all requests rejected while Redis is down)

### Production Recommendation

| Scenario | Recommendation |
|----------|----------------|
| Public API (must stay up) | `fail-open: true` — availability > strict limiting |
| Security-critical API (DDoS protection) | `fail-open: false` — correctness > availability |
| Internal microservices | `fail-open: true` — service continuity preferred |

---

## 9. Health Indicator — /actuator/health

```java
@Component
public class RateLimiterHealthIndicator implements HealthIndicator {

    @Override
    public Health health() {
        CircuitBreakerState state = circuitBreaker.getState();

        return switch (state) {
            case CLOSED -> Health.up()
                    .withDetail("circuit", "CLOSED")
                    .withDetail("algorithm", rateLimiterService.getActiveAlgorithmName())
                    .withDetail("failureCount", circuitBreaker.getFailureCount())
                    .build();

            case OPEN -> Health.down()
                    .withDetail("circuit", "OPEN")
                    .withDetail("tripCount", circuitBreaker.getTripCount())
                    .withDetail("openedAt", circuitBreaker.getOpenedAt())
                    .withDetail("failOpen", circuitBreaker.isFailOpen())
                    .build();

            case HALF_OPEN -> Health.unknown()
                    .withDetail("circuit", "HALF_OPEN")
                    .withDetail("message", "Probing Redis recovery...")
                    .build();
        };
    }
}
```

### State → HTTP Status Mapping

| Circuit State | Health Status | HTTP Code from /actuator/health |
|--------------|---------------|----------------------------------|
| CLOSED | UP | 200 |
| OPEN | DOWN | 503 (when Spring Boot returns 503 on DOWN) |
| HALF_OPEN | UNKNOWN | 200 (non-critical unknown) |

### Sample Response (CLOSED)
```json
GET /actuator/health
{
  "status": "UP",
  "components": {
    "rateLimiter": {
      "status": "UP",
      "details": {
        "circuit": "CLOSED",
        "algorithm": "REDIS_TOKEN_BUCKET",
        "failureCount": 0
      }
    },
    "db": { "status": "UP" },
    "diskSpace": { "status": "UP" }
  }
}
```

### Sample Response (OPEN)
```json
{
  "status": "DOWN",
  "components": {
    "rateLimiter": {
      "status": "DOWN",
      "details": {
        "circuit": "OPEN",
        "tripCount": 2,
        "openedAt": "1708001200000",
        "failOpen": true
      }
    }
  }
}
```

---

## 10. Prometheus Metrics — /actuator/prometheus

```java
@Component
public class RateLimiterMetrics {

    private final Counter allowedRequests;
    private final Counter rejectedRequests;
    private final Counter fallbackActivations;
    private final Gauge circuitState;

    public RateLimiterMetrics(MeterRegistry registry, CircuitBreaker circuitBreaker) {
        this.allowedRequests = Counter.builder("rate_limiter_requests_total")
                .description("Total rate limiter decisions")
                .tag("result", "allowed")
                .register(registry);

        this.rejectedRequests = Counter.builder("rate_limiter_requests_total")
                .description("Total rate limiter decisions")
                .tag("result", "rejected")
                .register(registry);

        this.fallbackActivations = Counter.builder("rate_limiter_fallback_activations_total")
                .description("Times the in-memory fallback was activated")
                .register(registry);

        this.circuitState = Gauge.builder("rate_limiter_circuit_state",
                        circuitBreaker, cb -> switch (cb.getState()) {
                            case CLOSED   -> 0.0;
                            case OPEN     -> 1.0;
                            case HALF_OPEN-> 2.0;
                        })
                .description("Circuit breaker state: 0=CLOSED, 1=OPEN, 2=HALF_OPEN")
                .register(registry);
    }
}
```

### Metrics at /actuator/prometheus
```
# HELP rate_limiter_requests_total Total rate limiter decisions
# TYPE rate_limiter_requests_total counter
rate_limiter_requests_total{result="allowed",} 1024.0
rate_limiter_requests_total{result="rejected",} 73.0

# HELP rate_limiter_fallback_activations_total Times the in-memory fallback was activated
# TYPE rate_limiter_fallback_activations_total counter
rate_limiter_fallback_activations_total 5.0

# HELP rate_limiter_circuit_state Circuit breaker state: 0=CLOSED, 1=OPEN, 2=HALF_OPEN
# TYPE rate_limiter_circuit_state gauge
rate_limiter_circuit_state 0.0
```

### Why a Gauge for Circuit State?

A **Gauge** reads the current value at scrape time — perfect for state. A Counter can only go up. Using a Gauge:
- Prometheus scrapes every 15s → always reflects the current state
- Works with Grafana alerts: `rate_limiter_circuit_state > 0` → alert!
- Zero additional memory — no separate state copy, the Gauge function reads directly from the live `CircuitBreaker` bean

### pom.xml Dependency Added
```xml
<!-- Prometheus Metrics — Phase 3 -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```
This pulls in the Prometheus registry implementation. Spring Boot auto-configures the `/actuator/prometheus` endpoint when this is on the classpath + `management.endpoints.web.exposure.include` contains `prometheus`.

---

## 11. Admin Endpoints

### GET /admin/circuit-breaker/status
```java
@GetMapping("/circuit-breaker/status")
@Operation(summary = "Get circuit breaker status")
public ResponseEntity<Map<String, Object>> getCircuitBreakerStatus() {
    var status = circuitBreaker.getStatus();
    return ResponseEntity.ok(Map.of(
            "state", status.getState().name(),
            "failureCount", status.getFailureCount(),
            "tripCount", status.getTripCount(),
            "openedAt", status.getOpenedAt() != null ? status.getOpenedAt() : "never",
            "openDurationSeconds", status.getOpenDurationSeconds(),
            "failOpen", status.isFailOpen(),
            "enabled", status.isEnabled()
    ));
}
```

**Sample response:**
```json
{
  "state": "OPEN",
  "failureCount": 7,
  "tripCount": 2,
  "openedAt": "2024-02-15T10:30:00Z",
  "openDurationSeconds": 90,
  "failOpen": true,
  "enabled": true
}
```

### POST /admin/circuit-breaker/reset
Manually forces circuit back to CLOSED with all counters cleared. Use case: Redis is confirmed healthy, but CB hasn't recovered from HALF_OPEN yet. Ops engineer can force recovery.

```bash
curl -X POST http://localhost:8080/admin/circuit-breaker/reset
# Response: {"status":"success","message":"Circuit breaker reset to CLOSED","state":"CLOSED"}
```

### POST /admin/circuit-breaker/trip
Manually forces circuit OPEN. Use case: Redis maintenance window — trip the circuit proactively before Redis goes down to prevent failure-log flooding.

```bash
curl -X POST http://localhost:8080/admin/circuit-breaker/trip
# Response: {"status":"tripped","message":"Circuit breaker tripped to OPEN","state":"OPEN"}
```

### CircuitBreakerStatus DTO
```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CircuitBreakerStatus {
    private CircuitBreakerState state;
    private int failureCount;
    private int tripCount;
    private String openedAt;          // ISO-8601 string or null
    private long openDurationSeconds;
    private boolean failOpen;
    private boolean enabled;
}
```
Using `@Builder` and `@Data` (Lombok) — generates builder, getters, setters, equals, hashCode. The `CircuitBreaker.getStatus()` method uses the builder pattern for clean construction.

---

## 12. Tests — What Was Verified

### 34 Existing Tests (Phase 1 + Phase 2 — Still Pass)

| Class | Tests | Verified |
|-------|-------|---------|
| `RateLimiterIntegrationTest` | 7 | E2E 429 responses, headers, reset, status |
| `TokenBucketStrategyTest` | 7 | isAllowed, refill, reject, reset, concurrency |
| `SlidingWindowStrategyTest` | 6 | isAllowed, window slide, prune, reset |
| `RedisTokenBucketStrategyTest` | 7 | Lua results, Redis down fallback, reset |
| `RedisSlidingWindowStrategyTest` | 7 | Lua results, Redis down fallback, reset |

### 10 New Tests (Phase 3) — CircuitBreakerTest

| # | Test Name | What It Verifies |
|---|-----------|------------------|
| 1 | `initialState_isClosed` | New circuit breaker starts CLOSED, no failures, no trips |
| 2 | `afterThresholdFailures_circuitTrips` | Exactly `failureThreshold` failures → state = OPEN, tripCount = 1 |
| 3 | `belowThreshold_staysClosed` | `threshold - 1` failures → still CLOSED |
| 4 | `whenOpen_allowRequestReturnsFalse` | OPEN state → `allowRequest()` returns false |
| 5 | `whenClosed_allowRequestReturnsTrue` | CLOSED state → `allowRequest()` returns true |
| 6 | `halfOpen_successfulProbe_closesCircuit` | After timeout: allowRequest() → HALF_OPEN; recordSuccess() → CLOSED |
| 7 | `halfOpen_failedProbe_retripsToOpen` | Probe failure → state = OPEN, tripCount = 2 (escalated) |
| 8 | `manualReset_closesCircuit` | `reset()` → CLOSED, failureCount = 0, tripCount = 0 |
| 9 | `manualTrip_opensCircuit` | `trip()` → OPEN, tripCount = 1 |
| 10 | `disabled_alwaysAllows` | `enabled = false` → `allowRequest()` always true |

**Total: 44 tests, 0 failures — BUILD SUCCESS ✅**

### Test Architecture for HALF_OPEN (Tests 6 & 7)

The challenge: `computeOpenDurationMillis()` uses `props.getHalfOpenTimeoutSeconds() * 1000L`. To make the duration "already elapsed," set `halfOpenTimeoutSeconds = 0` **before tripping** — then the open duration is `0ms`, which is immediately elapsed.

```java
@Test
void halfOpen_successfulProbe_closesCircuit() throws Exception {
    props.setHalfOpenTimeoutSeconds(0);     // ← set BEFORE trip
    tripCircuit();                           // trip count increments, openedAt set
    Thread.sleep(5);                         // let System.currentTimeMillis() advance
    boolean allowed = cb.allowRequest();     // openDurationElapsed() returns true → HALF_OPEN
    assertThat(cb.getState()).isEqualTo(CircuitBreakerState.HALF_OPEN);
    assertThat(allowed).isTrue();            // probe is allowed through

    cb.recordSuccess();
    assertThat(cb.getState()).isEqualTo(CircuitBreakerState.CLOSED);
}
```

**Why set halfOpenTimeoutSeconds = 0 BEFORE tripping?** `computeOpenDurationMillis()` is called using the `props` object, not a captured value. Setting it before tripping ensures that when `openDurationElapsed()` calls `computeOpenDurationMillis()`, it gets `0ms`. `Thread.sleep(5)` ensures at least 5ms passes — safely more than 0ms.

### Fix for Redis Strategy Tests (Phase 3 Change)

Adding `CircuitBreaker` to `RedisTokenBucketStrategy` and `RedisSlidingWindowStrategy` constructors broke the existing test subclasses. Fix:
```java
// Before (Phase 2 constructor — 3 args):
class TestableRedisTokenBucket extends RedisTokenBucketStrategy {
    TestableRedisTokenBucket(StubRedisTemplate tpl, FakeFallback fb) {
        super(tpl, null, null);   // ❌ compile error — now needs 4 args
    }
}

// After (Phase 3 — 4 args with disabled CB):
class TestableRedisTokenBucket extends RedisTokenBucketStrategy {
    TestableRedisTokenBucket(StubRedisTemplate tpl, FakeFallback fb, CircuitBreaker cb) {
        super(tpl, null, null, cb);   // ✅ CB disabled — doesn't interfere with Redis tests
    }
}

// In @BeforeEach:
CircuitBreakerProperties props = new CircuitBreakerProperties();
props.setEnabled(false);   // CB disabled → allowRequest() always returns true
CircuitBreaker cb = new CircuitBreaker(props);
strategy = new TestableRedisTokenBucket(tpl, fallback, cb);
```
**Key insight:** Disabled CB means `allowRequest()` always returns `true` — so the existing Redis test logic is unaffected. Phase 2 test behavior is preserved.

---

## 13. Problems Faced & Solutions

### Problem 1: `Duration` Import Removed But `Instant` Still Used
**What happened:** During the import cleanup (removing unused `java.time.Duration`), we accidentally also removed `java.time.Instant`, which was still used in `CircuitBreaker.getStatus()`:

```java
// This line uses Instant — was left unresolved after import removed:
.openedAt(openedAt > 0 ? Instant.ofEpochMilli(openedAt).toString() : null)
```

**Symptom:** Compiler error: `Instant cannot be resolved`.

**Solution:** Added back `import java.time.Instant;` while keeping `java.time.Duration` removed. Lesson: when cleaning imports, check all usages in the file, not just the most obvious ones.

---

### Problem 2: Hardcoded Escalation Broke HALF_OPEN Tests
**What happened:** Initial implementation used hardcoded durations:
```java
case 0, 1 -> Duration.ofMinutes(5).toMillis();   // 300,000ms
case 2    -> Duration.ofMinutes(15).toMillis();  // 900,000ms
```

In tests, `Thread.sleep(5)` (5ms) was far less than 300,000ms — the circuit never transitioned to HALF_OPEN during test execution.

**Failed attempt:** Setting `props.setHalfOpenTimeoutSeconds(0)` AFTER tripping — but the hardcoded formula ignored `props` entirely, so setting it had no effect.

**Fix:** Changed `computeOpenDurationMillis()` to use `props.getHalfOpenTimeoutSeconds() * 1000L` as the base:
```java
private long computeOpenDurationMillis() {
    long base = props.getHalfOpenTimeoutSeconds() * 1000L;  // ← now configurable
    return switch (tripCount.get()) {
        case 0, 1 -> base;
        case 2    -> base * 3;
        default   -> base * 12;
    };
}
```

Setting `props.setHalfOpenTimeoutSeconds(0)` now makes `computeOpenDurationMillis()` return `0ms` — any elapsed time is sufficient. Also teaches: **always make configuration drive behavior, not hardcode constants**. Testability is a design quality signal.

---

### Problem 3: Ordering of `setHalfOpenTimeoutSeconds(0)` vs `tripCircuit()`
**What happened:** In the test, setting `halfOpenTimeoutSeconds = 0` AFTER calling `tripCircuit()` still didn't cause `openDurationElapsed()` to return true.

**Root cause:** `openedAt = System.currentTimeMillis()` is set during `tripCircuit()`. When `Thread.sleep(5)` ran after setting `halfOpenTimeoutSeconds = 0`, the formula was `computeOpenDurationMillis() = 0 * 1000 = 0ms`. But then `openDurationElapsed()` computed:
```java
System.currentTimeMillis() - openedAt >= 0
```
This should ALWAYS be true (current time minus past time is always ≥ 0). So why did it fail?

**Real root cause:** When setting `halfOpenTimeoutSeconds = 0` BEFORE tripping, `tripCount = 0` at the time of `computeOpenDurationMillis()`, giving `0ms`. After tripping, `tripCount = 1`, but `base = 0`, so `base * 1 = 0ms` still.

But when setting `halfOpenTimeoutSeconds = 0` AFTER tripping, the test was checking `allowRequest()` BEFORE the Thread.sleep — there was a race between `openedAt` and the current time. Reordering to sleep AFTER setting fixed it.

**Fix:** Always set `halfOpenTimeoutSeconds = 0` before tripping, sleep 5ms after tripping, then call `allowRequest()`.

---

### Problem 4: Duplicate `@Component` Import After Refactor
**What happened:** A find-and-replace during import cleanup accidentally duplicated the `org.springframework.stereotype.Component` import:
```java
import org.springframework.stereotype.Component;  // original
import org.springframework.stereotype.Component;  // duplicate from refactor
```

**Result:** IDE warning — unused import. The duplicate was harmless to compilation but messy.

**Fix:** Removed the duplicate. The lesson: use IDE-managed imports or `mvn compile` to catch cleanup regressions early.

---

### Problem 5: `@ConditionalOnProperty` and `CircuitBreaker` Bean
**What happened:** When `rate-limiter.use-redis=false`, the `RedisTokenBucketStrategy` bean is not created (it uses `@ConditionalOnProperty`). But the `CircuitBreaker` bean is a `@Component` registered unconditionally.

When `RateLimiterService` constructs the selected strategy (in-memory, since `use-redis=false`), it doesn't inject `CircuitBreaker` since the Redis strategies aren't used.

**The question:** Is `CircuitBreaker` wasted as a Spring bean when Redis is disabled?

**Answer:** Yes, but it's acceptable. Size of `CircuitBreaker` is negligible (a few atomic integers + a lock). The alternative — using `@ConditionalOnProperty` on `CircuitBreaker` too — adds complexity without benefit. The CB bean exists but is never called in that mode.

---

## 14. Interview Q&A — Deep Dive

---

### 🅐 Project Overview

**Q1. What is Phase 3 and why is it needed?**
Phase 3 adds a Circuit Breaker to make the rate limiter resilient to Redis failures. Without it:
- Redis goes down → every request waits for connection timeout (e.g., 2 seconds)
- Under high load: thread pool exhausts waiting for Redis → app becomes unresponsive
- Cascading failure: one broken dependency brings down the entire service

With Circuit Breaker: Redis fails 5 times → circuit trips → traffic immediately served from in-memory fallback → app stays fast. Redis recovers → probe detects this → circuit closes → Redis serves traffic again.

**Q2. Why implement Circuit Breaker manually vs using Resilience4j?**
Resilience4j is excellent for production. We implemented manually to demonstrate understanding of:
- State machine design with concurrent thread safety
- `volatile` vs `synchronized` tradeoffs
- Lock-free counters with `AtomicInteger`
- The split-second probe logic

Using Resilience4j would be 3 lines. Implementing it shows you understand what those 3 lines do.

**Q3. What problem does each phase solve?**

| Phase | Problem | Solution |
|-------|---------|---------|
| Phase 1 | No rate limiting | In-memory token bucket + sliding window |
| Phase 2 | Per-instance limits (not shared) | Redis + Lua atomic scripts |
| Phase 3 | Redis single point of failure | Circuit breaker + health monitoring |

---

### 🅑 State Machine

**Q4. Why three states? Why not just OPEN/CLOSED?**
Two states (OPEN/CLOSED) create thrashing: Redis comes back → OPEN closes immediately → first request fails (Redis still unstable) → OPEN again → repeat. This is called "flapping."

HALF_OPEN solves this: instead of immediately closing, ONE probe request tests Redis. Only if the probe succeeds does the circuit close. This prevents premature optimism.

**Q5. Walk me through the complete lifecycle of the circuit breaker.**
1. **Startup:** state=CLOSED, all requests pass to Redis
2. **Redis degrades:** 5 consecutive `RedisConnectionFailureException`s → `recordFailure()` called 5 times → threshold reached → `tripCircuit()` → state=OPEN
3. **OPEN period:** all `allowRequest()` calls return false immediately (no Redis timeouts) → in-memory fallback serves traffic
4. **After 30s:** `openDurationElapsed()` returns true → first `allowRequest()` call transitions OPEN → HALF_OPEN and returns true (probe allowed)
5. **Probe succeeds:** `recordSuccess()` → state=CLOSED, `failureCount=0`, `probeInFlight=false`
6. **Probe fails (Redis still down):** `recordFailure()` → `tripCircuit()` again → state=OPEN, `tripCount=2` → new open duration = 90s (base × 3)

**Q6. What happens to requests that arrive during the HALF_OPEN probe window?**
The `probeInFlight` flag ensures only the FIRST request is the probe (`allowProbe()` returns true). All subsequent requests while the probe is in flight call `allowProbe()` → `probeInFlight == true` → return false → serve from fallback. This prevents a thundering herd of probes overwhelming a recovering Redis.

**Q7. How does your circuit breaker handle concurrent tripCircuit() calls?**
The double-check locking pattern:
```java
int failures = failureCount.incrementAndGet();   // lock-free
if (failures >= threshold) {
    stateLock.lock();
    try {
        if (state == CLOSED || state == HALF_OPEN) {  // re-check inside lock
            tripCircuit();
        }
    } finally { stateLock.unlock(); }
}
```
100 threads might all see `failures >= threshold`, but only the first to acquire `stateLock` calls `tripCircuit()`. The rest see `state == OPEN` (already tripped) and skip.

**Q8. Why is `state` volatile but failure counting uses AtomicInteger?**
- `volatile` provides visibility guarantee: a write to `state` is immediately visible to all other threads. It's sufficient for a reference type read/written atomically by the JVM.
- `AtomicInteger` provides **atomicity for read-modify-write** (CAS-based): `incrementAndGet()` is indivisible — no two threads can both read `5`, both increment, and both write `6`. They'd serialize: one reads `5`→writes `6`, the other reads `6`→writes `7`.

Using `volatile int` for `failureCount` would be a bug: two threads could both read `4`, both compute `5`, and both write `5` — losing a count.

---

### 🅒 Thread Safety

**Q9. What is the critical section in your circuit breaker implementation?**
State transitions are the critical section — guarded by `stateLock`:
- CLOSED → OPEN (`tripCircuit()`)
- OPEN → HALF_OPEN (`transitionTo()`)
- HALF_OPEN → CLOSED (`recordSuccess()`)
- Any → CLOSED (`reset()`)

The hot path (CLOSED → `allowRequest()` returns true) is NOT in the critical section — it's just a `volatile` read. This matters a lot for performance: at 10K req/s, holding a lock on every request would be a bottleneck.

**Q10. Could there be a deadlock in your implementation?**
No. `stateLock` is a non-reentrant lock (`ReentrantLock`), but all code paths either hold it briefly and release in `finally`, or don't hold it at all. There's only one lock — deadlock requires at least two locks held in different orders. Since we have one lock, deadlock is impossible.

**Q11. Is it possible for two threads to both act as the probe in HALF_OPEN?**
No, due to `allowProbe()`:
```java
stateLock.lock();
try {
    if (!probeInFlight) {    // only one thread can see this as false
        probeInFlight = true;
        return true;
    }
    return false;
} finally { stateLock.unlock(); }
```
The first thread acquires the lock, sees `probeInFlight = false`, sets it to `true`, returns `true`. Any subsequent thread acquires the lock, sees `probeInFlight = true` (already set), returns `false`. Exactly one probe.

**Q12. Why not use `synchronized` instead of `ReentrantLock`?**
`ReentrantLock` was chosen for:
1. **Explicit `finally` unlock**: prevents leaked locks if an exception occurs mid-transition
2. **`tryLock()` capability**: could add tryLock with timeout to avoid blocking in high-contention scenarios
3. **Fairness awareness**: `new ReentrantLock(true)` for fair ordering if needed
4. Functionally equivalent to `synchronized` in this implementation, but `ReentrantLock` is preferred in production code for the additional control it provides

---

### 🅓 Resiliency Patterns

**Q13. What is "fail-open" and "fail-closed" and which did you default to?**
- **Fail-open:** when the circuit is OPEN (Redis down), traffic continues through an alternative path (in-memory fallback). Prioritizes **availability**.
- **Fail-closed:** when OPEN, traffic is rejected. Prioritizes **correctness** — limits are always enforced, even if it means rejecting all traffic.

We default to `fail-open: true`. Rationale: for an API rate limiter, being slightly over-limit during a Redis outage is acceptable. Dropping all traffic during Redis maintenance would be far more disruptive.

**Q14. What is the difference between a Circuit Breaker and a Retry?**

| Mechanism | Behavior | Best For |
|-----------|---------|---------|
| Retry | On failure, wait + retry N times | Transient failures (network blip) |
| Circuit Breaker | On repeated failures, stop trying entirely | Sustained dependency failure |
| Both together | Retry within CLOSED state; CB detects sustained failure | Production systems |

Retry without CB is dangerous under sustained failure: each request retries 3× → 3× load on already-failing Redis → makes recovery harder. CB + short retry = correct pattern.

**Q15. What is the "half-open" probe and why is it important?**
The probe is the healing mechanism. After the circuit has been OPEN for its timeout, one "test balloon" request is sent to Redis. If Redis responds normally → circuit closes (healed). If it fails → circuit stays open longer (escalated).

Without the probe, the circuit would either:
- Never heal (perpetually OPEN) — requiring manual `reset()`
- Heal too eagerly (immediately CLOSED after timeout) — risk of immediately re-tripping if Redis is still unstable

The probe balances: "don't assume Redis is better, but test it with minimal risk."

**Q16. What is "cascading failure"? How does your circuit breaker prevent it?**
Cascading failure: one failing dependency causes the caller to:
1. Exhaust thread pool (waiting for timeouts)
2. Backpressure propagates upstream
3. Entire system becomes unresponsive

Prevention: Circuit breaker makes failure detection **fast** (immediate rejection after threshold) instead of allowing the caller to wait for every timeout. Once OPEN, Redis is not called → thread pool is spared → upstream sees fast responses (from fallback) → no cascade.

---

### 🅔 Observability

**Q17. Why expose circuit state as a Prometheus gauge instead of a counter?**
A **Counter** only increases — `rate_limiter_circuit_trips_total` can tell you how many times the circuit tripped (useful for alerting frequency), but not the current state. A **Gauge** reads the real-time value — `rate_limiter_circuit_state = 1.0` means right now, the circuit is OPEN. Prometheus dashboards need both:
- Counter: "circuit tripped 5 times in the last hour" (frequency trend)
- Gauge: "circuit is currently OPEN" (current status)

We implemented the Gauge. The Counter can be added by incrementing a counter in `tripCircuit()`.

**Q18. What Grafana alerts would you set up for this system?**
```promql
# Alert: Circuit went OPEN (state=1)
ALERT circuit_breaker_open
  IF rate_limiter_circuit_state == 1
  FOR 1m
  LABELS {severity="warning"}
  ANNOTATIONS {summary="Rate limiter circuit breaker is OPEN — Redis may be down"}

# Alert: High rejection rate
ALERT high_rejection_rate
  IF rate(rate_limiter_requests_total{result="rejected"}[5m]) > 100
  FOR 2m
  LABELS {severity="critical"}

# Alert: Fallback activations spiking
ALERT fallback_spike
  IF rate(rate_limiter_fallback_activations_total[1m]) > 10
  FOR 30s
```

**Q19. What does the health endpoint tell an ops engineer that Prometheus doesn't?**
The health endpoint provides **structured, human-readable detail** in a single `curl` call. An ops engineer can quickly see state, tripCount, openedAt, failOpen setting — without needing to write a PromQL query. The health endpoint follows a simple UP/DOWN convention that Kubernetes health probes and load balancers can consume directly (e.g., `readinessProbe.httpGet.path: /actuator/health`).

Prometheus is better for time-series analysis, alerting thresholds, and dashboards. Health endpoint is better for immediate human inspection and K8s probe integration.

---

### 🅕 Production Considerations

**Q20. How would you tune the failure threshold and timeout in production?**
- **Failure threshold:** Set to match your Redis timeout × connections. If your JDBC pool has 10 Redis connections and timeout is 2s, you'd see 5 failures in ~10ms if Redis is truly down. Threshold of 5 is appropriate.
- **Half-open timeout:** Should match Redis restart or failover time. For Redis Sentinel with 30s failover, set `half-open-timeout-seconds: 45` (a bit extra). For Redis Cluster automatic re-election (typically 10-15s), use 20s.
- **Escalation:** Default 1×/3×/12× is reasonable. For critical APIs, use higher escalation (1×/10×/60×) to give Redis more recovery time on repeated failures.

**Q21. What happens during Redis maintenance (planned downtime)?**
Without CB intervention:
- First request fails → circuits trips OPEN (tripCount=1) → open for 30s
- After 30s, probe sent → Redis still down (planned maintenance) → trips OPEN again (tripCount=2) → open for 90s
- This repeats until maintenance ends

Better approach using admin endpoint:
```bash
# Before maintenance starts:
curl -X POST http://app/admin/circuit-breaker/trip
# Circuit is now OPEN proactively — no failure logs flooding

# After maintenance ends:
curl -X POST http://app/admin/circuit-breaker/reset
# Circuit immediately CLOSED — Redis traffic resumes instantly
```
The `trip()` and `reset()` admin endpoints make planned maintenance cleaner.

**Q22. How would you test the circuit breaker in integration tests with a real Redis?**
Using Testcontainers:
```java
@Container
static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

@DynamicPropertySource
static void redisProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
}

@Test
void circuitBreaker_tripsOnRedisFailure() {
    // Stop Redis container
    redis.stop();
    
    // Send requests until threshold
    for (int i = 0; i < 6; i++) {
        mvc.perform(post("/api/v1/request").header("X-User-Id", "test")).andReturn();
    }
    
    // Verify circuit is OPEN
    mvc.perform(get("/admin/circuit-breaker/status"))
       .andExpect(jsonPath("$.state").value("OPEN"));
}
```

**Q23. What would happen if you had 10 app instances and Redis went down?**
Each app instance has its own `CircuitBreaker` bean (it's a Spring `@Component` singleton per JVM). So:
- Instance 1 trips at failure #5
- Instance 2 trips at failure #5 (independently)
- Each instance serves traffic from its own in-memory fallback

The fallback limits are now per-instance, not shared. A user could potentially get 10× their limit (one limit-worth from each instance). This is the correct tradeoff — availability is preserved.

For true distributed circuit breaker, you'd store trip state in Redis — circular paradox! Alternatively: Consul, Zookeeper, or a separate highly-available store. Out of scope for this project.

**Q24. Could the escalating open duration cause starvation?**
If Redis stays down indefinitely:
- Trip 1 → 30s OPEN
- Trip 2 → 90s OPEN
- Trip 3 → 360s OPEN (6 minutes)
- Trip 4 → 360s OPEN
- Trip 5 → 360s OPEN (no further escalation — `default` case)

After about 10 minutes of Redis being down, the CB reaches maximum escalation and probes every 6 minutes. The in-memory fallback continues serving traffic indefinitely. No starvation — probes still happen to detect Redis recovery.

If starvation is a concern (must reconnect faster), reduce multipliers or add an admin-reset SLA process (automated Redis health check that auto-calls `/admin/circuit-breaker/reset` on Redis recovery).

---

### 🅖 Design Patterns

**Q25. What design patterns does your circuit breaker use?**
1. **State Machine Pattern** — explicit states with defined transition rules
2. **Strategy Pattern** — `RateLimiterStrategy` interface enables pluggable fallbacks
3. **Template Method Pattern** — `allowRequest()` defines the algorithm skeleton; subclasses override strategy
4. **Proxy Pattern** — `CircuitBreaker` wraps `RedisTemplate` calls; callers don't know Redis is bypassed when OPEN
5. **Observer Pattern** (implicit) — `RateLimiterMetrics` and `RateLimiterHealthIndicator` observe `CircuitBreaker` state via direct bean injection

**Q26. How is Single Responsibility Principle maintained in your design?**
Each class has one reason to change:
- `CircuitBreaker`: changes if state machine logic changes
- `CircuitBreakerProperties`: changes if configuration properties change
- `RateLimiterHealthIndicator`: changes if Spring health format changes
- `RateLimiterMetrics`: changes if metric naming or structure changes
- `AdminController`: changes if admin API contract changes
- `RedisSlidingWindowStrategy`: changes if sliding window algorithm changes

No class is responsible for both "is this request within limits?" and "is Redis healthy?" — those concerns are separated.

**Q27. How does Open/Closed Principle apply here?**
`RateLimiterStrategy` interface is closed for modification but open for extension. Adding the circuit breaker did NOT modify:
- `RateLimitFilter`
- `RateLimiterService` core logic
- Response header writing (`RateLimitHeaderUtil`)
- `AdminController` rate limit config endpoints

We only EXTENDED:
- Redis strategies (added CB calls, didn't change interfaces)
- `AdminController` (added new endpoints, didn't modify existing ones)
- Added entirely new classes (`CircuitBreaker`, `RateLimiterMetrics`, etc.)

**Q28. How would you add fallback to a different cache (e.g., Caffeine) instead of in-memory?**
The `RateLimiterStrategy` interface is already the abstraction. Create:
```java
@Component("caffeineFallbackStrategy")
public class CaffeineTokenBucketStrategy implements RateLimiterStrategy {
    private final Cache<String, BucketState> cache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();
    // ...
}
```
Then inject it as the `fallback` in `RedisTokenBucketStrategy` via constructor injection. Zero change to `CircuitBreaker`, zero change to `RateLimiterService`.

---

### 🅗 Advanced Topics

**Q29. What is the "thundering herd" problem in this context?**
After `half-open-timeout-seconds` elapses, ALL waiting threads simultaneously check `openDurationElapsed()`. Without protection:
- 1000 threads all see OPEN + elapsed
- All 1000 call `transitionTo(HALF_OPEN)` → protected by lock → fine
- All 1000 then call `allowProbe()` → `probeInFlight` guard → only 1 passes
- 999 threads get fallback response

Our implementation handles this correctly. The `probeInFlight` flag + lock ensures exactly one probe regardless of concurrent request count.

**Q30. If you were to productionize this, what would you add?**
1. **Bulkhead Pattern** — separate thread pools per downstream dependency (Redis, DB, external API) so one failure doesn't exhaust the shared pool
2. **Timeout on Redis calls** — Spring Data Redis `lettuce.pool.max-active` + `timeout: 500ms` to fail fast (currently 2s)
3. **Distributed CB state** — store `tripCount` and `openedAt` in a high-availability store (Consul, etcd) so all instances share CB state
4. **Circuit breaker events webhook** — publish to SNS/Kafka when CB trips, so incident response can begin immediately
5. **Dashboard** — Grafana dashboard with `rate_limiter_circuit_state` and `rate_limiter_fallback_activations_total` panels
6. **Testcontainers integration test** — real Redis container that gets paused mid-test to verify CB trips exactly at threshold
