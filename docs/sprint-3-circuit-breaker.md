# 🏁 Sprint 3 — Circuit Breaker + Final Submission (Phase 3)

> **Duration:** Week 5 – Week 6  
> **Goal:** Add Circuit Breaker pattern for resiliency + complete all submission requirements  
> **Phase 3 Choice:** Option B — Resiliency & Circuit Breaker  
> **Points at stake:** 10 pts (Phase 3) + final submission  
> **Status:** 🔲 TODO

---

## 🎯 Sprint Goal

Wrap the Redis rate limiter with a **Circuit Breaker** that detects when Redis is degraded or down, switches between `CLOSED → OPEN → HALF-OPEN` states, implements escalating block durations, and produces a clean final submission.

---

## 📋 User Stories

| # | Story | Acceptance Criteria | Status |
|---|-------|---------------------|--------|
| US-13 | As an ops engineer, I want rate limiter to stay up even when Redis fails | Circuit opens, app fails gracefully (open or closed) | 🔲 Todo |
| US-14 | As an ops engineer, I want to configure fail-open vs fail-closed | `rate-limiter.circuit-breaker.fail-open=true` works | 🔲 Todo |
| US-15 | As an ops engineer, I want escalating block durations for bad actors | 5min → 15min → 1hr auto-escalation | 🔲 Todo |
| US-16 | As a developer, I want circuit breaker state in the health endpoint | `/actuator/health` shows circuit state | 🔲 Todo |
| US-17 | As an evaluator, I want a 2-min demo video showing all features | Screen recording uploaded | 🔲 Todo |

---

## 🔁 Circuit Breaker State Machine

```
                    ┌─────────────────────────────────────────┐
                    │                                         │
               failures > threshold                    success
                    │                                         │
                    ▼                                         │
        ┌──────────────────────┐                             │
        │   CLOSED (Normal)    │◄────────────────────────────┘
        │   All requests pass  │
        │   Monitor errors     │
        └──────────┬───────────┘
                   │
           errors > threshold
           (e.g. 5 failures in 60s)
                   │
                   ▼
        ┌──────────────────────┐
        │   OPEN (Tripped)     │
        │   Reject all OR      │
        │   Allow all          │
        │   (fail-open mode)   │
        └──────────┬───────────┘
                   │
           after OPEN timeout
           (e.g. 30 seconds)
                   │
                   ▼
        ┌──────────────────────┐
        │   HALF-OPEN          │
        │   Allow 1 probe req  │
        │   If success → CLOSED│
        │   If fail → OPEN     │
        └──────────────────────┘
```

### Escalating Block Durations
```
1st trip   → OPEN for 5 minutes
2nd trip   → OPEN for 15 minutes
3rd trip   → OPEN for 1 hour
4th trip+  → OPEN for 1 hour (max)
```

---

## 📋 Tasks Breakdown

### Week 5 — Circuit Breaker Implementation

#### Task 1: Circuit Breaker Core

**Create:** `src/main/java/com/ratelimiter/circuitbreaker/CircuitBreaker.java`

```java
public class CircuitBreaker {
    enum State { CLOSED, OPEN, HALF_OPEN }

    private volatile State state = State.CLOSED;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger tripCount = new AtomicInteger(0);
    private volatile long openedAt = 0;

    // Config
    private final int failureThreshold;    // e.g. 5 failures
    private final int windowSeconds;       // monitoring window
    private final boolean failOpen;        // true = allow when OPEN

    public boolean allowRequest() { ... }
    public void recordSuccess() { ... }
    public void recordFailure() { ... }
    private long getOpenDuration() {
        return switch(tripCount.get()) {
            case 0, 1 -> Duration.ofMinutes(5).toMillis();
            case 2    -> Duration.ofMinutes(15).toMillis();
            default   -> Duration.ofHours(1).toMillis();
        };
    }
}
```

---

#### Task 2: Circuit Breaker Configuration

**Create:** `src/main/java/com/ratelimiter/circuitbreaker/CircuitBreakerProperties.java`

```yaml
# application.yml additions
rate-limiter:
  circuit-breaker:
    enabled: true
    failure-threshold: 5          # failures to trip circuit
    window-seconds: 60            # monitoring window
    half-open-timeout-seconds: 30 # time before probe attempt
    fail-open: true               # true=allow all when OPEN, false=block all
```

---

#### Task 3: Integrate Circuit Breaker with Redis Strategies

**Modify:** `RedisTokenBucketStrategy.java` and `RedisSlidingWindowStrategy.java`

```
Before Redis call:
  circuitBreaker.allowRequest()?
    NO (OPEN + fail-closed) → throw RateLimiterUnavailableException
    NO (OPEN + fail-open)   → return true (allow all)
    YES                     → proceed

After Redis call:
  Success → circuitBreaker.recordSuccess()
  Failure → circuitBreaker.recordFailure()
             if failures > threshold → circuit OPENS
```

---

#### Task 4: Circuit Breaker Admin Endpoints

**Add to `AdminController.java`:**

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/admin/circuit-breaker/status` | Get current state (CLOSED/OPEN/HALF_OPEN) |
| `POST` | `/admin/circuit-breaker/reset` | Manually reset to CLOSED |
| `POST` | `/admin/circuit-breaker/trip` | Manually trip to OPEN (for testing) |

---

#### Task 5: Health Indicator

**Create:** `src/main/java/com/ratelimiter/health/RateLimiterHealthIndicator.java`

```java
@Component
public class RateLimiterHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        return switch (circuitBreaker.getState()) {
            case CLOSED    -> Health.up()
                               .withDetail("circuit", "CLOSED")
                               .withDetail("algorithm", strategy.getAlgorithmName())
                               .build();
            case OPEN      -> Health.down()
                               .withDetail("circuit", "OPEN")
                               .withDetail("tripCount", circuitBreaker.getTripCount())
                               .build();
            case HALF_OPEN -> Health.unknown()
                               .withDetail("circuit", "HALF_OPEN")
                               .build();
        };
    }
}
```

---

### Week 6 — Final Polish & Submission

#### Task 6: Prometheus Metrics (Bonus)

**Add to `pom.xml`:**
```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

**Metrics to expose:**
```
rate_limiter_requests_total{identifier, result="allowed|rejected"}
rate_limiter_circuit_state{state="CLOSED|OPEN|HALF_OPEN"}
rate_limiter_redis_latency_seconds
rate_limiter_fallback_activations_total
```

**`application.yml`:**
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus, metrics
  metrics:
    export:
      prometheus:
        enabled: true
```

---

#### Task 7: Grafana Dashboard (Bonus)

**Create:** `docs/grafana-dashboard.json`

Key panels:
- Requests/sec (allowed vs rejected)
- Current circuit breaker state
- Redis latency percentiles (P50, P95, P99)
- Top 10 identifiers by request volume
- Rate limit violation rate (%)

Add `grafana/` service to `docker-compose.yml`:
```yaml
  grafana:
    image: grafana/grafana:10.0.0
    ports:
      - "3000:3000"
    volumes:
      - ./docs/grafana-dashboard.json:/etc/grafana/provisioning/dashboards/dashboard.json
```

---

#### Task 8: Postman Collection Export

- [ ] Create Postman collection: `docs/API-Rate-Limiter.postman_collection.json`
- [ ] Include all endpoints with example requests
- [ ] Add environment variables: `{{baseUrl}}`, `{{userId}}`
- [ ] Include pre-request scripts for rate limit header assertions
- [ ] Test scenarios:
  - Normal request flow (200 responses)
  - Rate limit breach (429 response)
  - Admin reset flow
  - Circuit breaker trip + recovery

---

#### Task 9: 2-Minute Demo Video Script

**Script outline:**
```
0:00 - 0:15  → Show running app: docker-compose up
0:15 - 0:40  → Demo rate limiting in Swagger UI
               Fire 10 requests → last returns 429 with headers
0:40 - 1:00  → Show admin reset endpoint working
1:00 - 1:20  → Demo Redis in redis-cli: HGETALL rate_limit:token:demo
1:20 - 1:40  → Demo circuit breaker: stop Redis → OPEN state
               Show /actuator/health → status DOWN
1:40 - 2:00  → Show test results: mvn test → 20+ tests passing
               Show GitHub repo, README, Swagger
```

---

#### Task 10: Final Submission Checklist

```
GitHub Repository
├── ✅ Public repo: KrAnubhav/Production-Ready-API-Rate-Limiter
├── ✅ README.md with setup instructions + examples
├── ✅ docker-compose.yml (one-command startup)
├── 🔲 Postman collection (docs/API-Rate-Limiter.postman_collection.json)
├── 🔲 2-minute video demo
├── ✅ Architecture diagram (docs/architecture.md)
└── ✅ Swagger UI

Source Code
├── ✅ Token Bucket algorithm (thread-safe)
├── ✅ Sliding Window algorithm (thread-safe)
├── 🔲 Redis distributed strategies (Sprint 2)
├── 🔲 Circuit Breaker (Sprint 3)
├── ✅ Filter/Interceptor
├── ✅ Admin API (reset, status, config CRUD)
└── ✅ Configurable limits (DB + YAML)

Tests
├── ✅ 13 unit tests (TokenBucket + SlidingWindow)
├── ✅ 7 integration tests
├── 🔲 Redis strategy tests (Sprint 2)
└── 🔲 Circuit breaker tests (Sprint 3)
```

---

## 📁 New Files to Create in Sprint 3

```
src/main/java/com/ratelimiter/
├── circuitbreaker/
│   ├── CircuitBreaker.java                      ← NEW
│   ├── CircuitBreakerProperties.java            ← NEW  
│   └── CircuitBreakerState.java                 ← NEW (enum)
├── health/
│   └── RateLimiterHealthIndicator.java          ← NEW
└── metrics/
    └── RateLimiterMetrics.java                  ← NEW

src/test/java/com/ratelimiter/
└── circuitbreaker/
    └── CircuitBreakerTest.java                  ← NEW

docs/
├── API-Rate-Limiter.postman_collection.json     ← NEW
└── grafana-dashboard.json                       ← NEW (bonus)
```

---

## ✅ Sprint 3 Definition of Done

- [ ] Circuit Breaker with CLOSED/OPEN/HALF-OPEN states works
- [ ] Escalating durations: 5min → 15min → 1hr
- [ ] Fail-open AND fail-closed modes configurable
- [ ] `/actuator/health` reflects circuit state
- [ ] Admin endpoints to manually trip/reset circuit
- [ ] Prometheus metrics endpoint live at `/actuator/prometheus`
- [ ] Postman collection exported (all endpoints, test scenarios)
- [ ] 2-minute video demo recorded and ready to submit
- [ ] Public GitHub repo clean and pushed

---

## 🧪 Manual Verification Steps

```bash
# 1. Test Circuit Breaker states
# Start everything
docker-compose up --build

# Check health (should show CLOSED)
curl http://localhost:8080/actuator/health | python3 -m json.tool

# Trip circuit manually
curl -X POST http://localhost:8080/admin/circuit-breaker/trip

# Check health (should show OPEN)
curl http://localhost:8080/actuator/health

# Make a request (fail-open → 200, fail-closed → 503)
curl -X POST http://localhost:8080/api/v1/request -H "X-User-Id: test"

# Reset circuit
curl -X POST http://localhost:8080/admin/circuit-breaker/reset

# 2. Test escalating durations
# Check after 1st trip: 5 minute open duration
# Check after 2nd trip: 15 minute open duration

# 3. Check Prometheus metrics
curl http://localhost:8080/actuator/prometheus | grep rate_limiter
```

---

## 🎯 Final Score Estimate

| Category | Points | Confidence |
|----------|--------|------------|
| Core algorithms + headers | 40 pts | ✅ Certain |
| Documentation | 15 pts | ✅ Certain |
| Testing & Code quality | 20 pts | ✅ Certain |
| Phase 2 (Redis) | 15 pts | 🔲 Sprint 2 |
| Phase 3 (Circuit Breaker) | 10 pts | 🔲 Sprint 3 |
| **Total** | **100 pts** | **75 confirmed** |

---

## ⬅️ Previous: [Sprint 2 — Redis Distributed Limiting](./sprint-2-redis.md)
## 🏠 Back to: [Sprint Overview](./sprint-overview.md)
