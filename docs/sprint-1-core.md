# 🏗️ Sprint 1 — Core Rate Limiter (Phase 1)

> **Duration:** Week 1 – Week 2  
> **Goal:** Build the mandatory core rate limiter service  
> **Points at stake:** 40 (Core) + 35 (Docs + Tests) = 75 pts  
> **Status:** ✅ COMPLETE

---

## 🎯 Sprint Goal

Build a **production-grade, thread-safe API Rate Limiter** with two algorithms (Token Bucket + Sliding Window), a filter that intercepts all requests, configurable limits per identifier, and full documentation + tests.

---

## 📋 User Stories

| # | Story | Acceptance Criteria | Status |
|---|-------|---------------------|--------|
| US-01 | As a client, I want API requests rate-limited so abuse is prevented | HTTP 429 returned when limit exceeded | ✅ Done |
| US-02 | As a client, I want to know my rate limit status in every response | `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset` headers present | ✅ Done |
| US-03 | As a client, I want to know when to retry after being blocked | `Retry-After` header present on 429 response | ✅ Done |
| US-04 | As a client, my limit is tracked by user ID, API key, or IP | Priority: `X-User-Id` → `X-API-Key` → Client IP | ✅ Done |
| US-05 | As an admin, I want to reset a user's rate limit manually | `POST /admin/reset/{identifier}` works | ✅ Done |
| US-06 | As an admin, I want to create custom limits per user via API | `POST /admin/config` creates DB-backed config | ✅ Done |
| US-07 | As a developer, I want Swagger docs to explore the API | Swagger UI live at `/swagger-ui.html` | ✅ Done |

---

## ✅ Deliverables Completed

### Week 1 — Foundation
- [x] Spring Boot 3.2 project setup (Maven, Java 17)
- [x] `docker-compose.yml` — App + PostgreSQL + Redis services
- [x] `Dockerfile` — multi-stage production build
- [x] `application.yml` — configurable rate limiter defaults
- [x] `application-local.yml` — H2 in-memory for local dev (no Docker needed)
- [x] `ApiRateLimiterApplication.java` — main entry point

### Week 2 — Core Logic + APIs
- [x] **Token Bucket algorithm** (`TokenBucketStrategy.java`)
  - Thread-safe with `ConcurrentHashMap` + `synchronized` blocks
  - Time-based token refill
- [x] **Sliding Window algorithm** (`SlidingWindowStrategy.java`)
  - `ConcurrentLinkedDeque` for timestamp tracking
  - Auto-prune stale timestamps
- [x] **Strategy Pattern** — `RateLimiterStrategy` interface (easy algorithm swap)
- [x] **`RateLimiterService`** — orchestrates strategy selection + DB config lookup
- [x] **`RateLimitFilter`** — `OncePerRequestFilter` intercepts every request
- [x] **`RateLimitConfig`** JPA entity (PostgreSQL-backed per-identifier config)
- [x] **`RateLimitController`** — `/api/v1/request`, `/api/v1/status`, `/api/v1/ping`
- [x] **`AdminController`** — reset, status, full config CRUD
- [x] **`OpenApiConfig`** — Swagger UI with full API descriptions

---

## 🧪 Testing

### Unit Tests — `TokenBucketStrategyTest` (7 tests)
| # | Test Name | What It Verifies |
|---|-----------|-----------------|
| 1 | `testAllowsRequestWhenTokensAvailable` | Allows request when bucket full |
| 2 | `testRejectsRequestWhenBucketEmpty` | Rejects after N requests exhausted |
| 3 | `testTokensRefillAfterTime` | Tokens refill after sleep (refillRate) |
| 4 | `testConcurrentRequestsThreadSafe` | 20 threads — exactly N allowed, rest rejected |
| 5 | `testResetClearsTokenCount` | Reset restores full capacity |
| 6 | `testDifferentIdentifiersTrackedSeparately` | userA and userB have independent buckets |
| 7 | `testGetRemainingRequests` | Remaining decrements correctly |

### Unit Tests — `SlidingWindowStrategyTest` (6 tests)
| # | Test Name | What It Verifies |
|---|-----------|-----------------|
| 1 | `testAllowsRequestWithinLimit` | Allows up to N requests |
| 2 | `testRejectsRequestOverLimit` | Rejects N+1 request |
| 3 | `testWindowSlides` | Old timestamps expire, new requests allowed |
| 4 | `testDifferentIdentifiersTrackedSeparately` | Independent windows per identifier |
| 5 | `testResetClearsWindow` | Reset clears timestamp deque |
| 6 | `testGetRemainingRequests` | Remaining count accurate |

### Integration Tests — `RateLimiterIntegrationTest` (7 tests)
| # | Test Name | What It Verifies |
|---|-----------|-----------------|
| 1 | `testFirstNRequestsSucceedThenNextFails` | Full flow: 200s then 429 |
| 2 | `testRateLimitHeadersPresentOnAllResponses` | All 3 headers present |
| 3 | `testRetryAfterHeaderOnRateLimitExceeded` | `Retry-After` on 429 |
| 4 | `testResetEndpointClearsLimit` | Admin reset works end-to-end |
| 5 | `testDifferentIdentifiersTrackedSeparately` | Isolated tracking per user |
| 6 | `testStatusEndpointReturnsCorrectInfo` | Status endpoint accurate |
| 7 | `testRemainingDecrementsWithEachRequest` | Counter decrements correctly |

### Test Results
```
Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS — Total time: 16.787 s
```

---

## 🔧 Key Technical Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| Algorithm selection | Config-driven (`application.yml`) | Easy swap: `TOKEN_BUCKET` or `SLIDING_WINDOW` |
| Thread safety | `synchronized(entry)` on `RateLimitEntry` | Atomic refill + consume, no race conditions |
| Storage (Phase 1) | `ConcurrentHashMap` in-memory | Fast O(1) access, thread-safe |
| Config persistence | PostgreSQL via JPA | Per-identifier overrides survive restarts |
| Filter approach | `OncePerRequestFilter` | Transparent middleware, zero business logic pollution |
| Identifier priority | `X-User-Id` → `X-API-Key` → IP | Flexible, works for all client types |

---

## 📁 Files Created in Sprint 1

```
src/main/java/com/ratelimiter/
├── ApiRateLimiterApplication.java
├── config/
│   ├── RateLimiterProperties.java
│   └── OpenApiConfig.java
├── controller/
│   ├── RateLimitController.java
│   └── AdminController.java
├── dto/
│   ├── RateLimitResponse.java
│   └── RateLimitStatusResponse.java
├── exception/
│   └── RateLimitExceededException.java
├── filter/
│   └── RateLimitFilter.java
├── model/
│   ├── RateLimitConfig.java
│   └── RateLimitEntry.java
├── repository/
│   └── RateLimitConfigRepository.java
└── service/
    ├── RateLimiterService.java
    └── strategy/
        ├── RateLimiterStrategy.java
        ├── TokenBucketStrategy.java
        └── SlidingWindowStrategy.java

src/test/java/com/ratelimiter/
├── RateLimiterIntegrationTest.java
└── service/strategy/
    ├── TokenBucketStrategyTest.java
    └── SlidingWindowStrategyTest.java
```

---

## 🚀 How to Run (Sprint 1 Deliverable)

```bash
# Option 1: Local (no Docker needed)
mvn spring-boot:run -Dspring-boot.run.profiles=local

# Option 2: Full Docker stack
docker-compose up --build

# Run all tests
mvn test

# Swagger UI
open http://localhost:8080/swagger-ui.html
```

---

## ➡️ Next: [Sprint 2 — Redis Distributed Rate Limiting](./sprint-2-redis.md)
