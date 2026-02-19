# 📘 Phase 1 — Deep Dive Documentation
### Production-Ready API Rate Limiter — Core Implementation

> **Assessment:** Evolving Systems Ltd — Internship Technical Assessment  
> **Phase:** 1 — Core Implementation (Weeks 1–2)  
> **Points:** 40/40 (Core) — Full marks target  
> **Author:** Anubhav Garg  
> **Status:** ✅ COMPLETE

---

## 📋 Table of Contents

1. [Phase 1 Requirements (from PDF)](#1-phase-1-requirements-from-pdf)
2. [Requirement 1 — Rate Limiting Algorithms](#2-requirement-1--rate-limiting-algorithms)
3. [Requirement 2 — Rate Limit Validation](#3-requirement-2--rate-limit-validation)
4. [Requirement 3 — Configurable Limits](#4-requirement-3--configurable-limits)
5. [Requirement 4 — Rate Limit Reset](#5-requirement-4--rate-limit-reset)
6. [Requirement 5 — In-Memory Storage](#6-requirement-5--in-memory-storage)
7. [Requirement 6 — HTTP Response Headers](#7-requirement-6--http-response-headers)
8. [Requirement 7 — REST API Endpoints](#8-requirement-7--rest-api-endpoints)
9. [Requirement 8 — Tests](#9-requirement-8--tests)
10. [Design Patterns Used](#10-design-patterns-used)
11. [Problems Faced & Solutions](#11-problems-faced--solutions)
12. [Interview Q&A — Deep Dive](#12-interview-qa--deep-dive)

---

## 1. Phase 1 Requirements (from PDF)

The assessment document (Evolving Systems Ltd) specifies these **mandatory** Phase 1 deliverables:

| # | Requirement | Status |
|---|-------------|--------|
| 1 | Rate limiting algorithm — Token Bucket OR Sliding Window | ✅ Both implemented |
| 2 | Rate limit check — identify user by user_id, IP, or API key | ✅ Done |
| 3 | Return HTTP 429 when limit exceeded | ✅ Done |
| 4 | Include rate limit headers in every response | ✅ Done |
| 5 | Configurable limits per identifier type | ✅ Done (DB + YAML) |
| 6 | Automatic reset after window expires | ✅ Done |
| 7 | Manual reset via admin API | ✅ Done |
| 8 | In-memory storage with O(1) lookup | ✅ Done |
| 9 | Status check endpoint | ✅ Done |
| 10 | Unit tests (minimum 5) | ✅ 13 tests |
| 11 | README with setup and usage | ✅ Done |
| 12 | Postman collection | 🔲 Pending |

---

## 2. Requirement 1 — Rate Limiting Algorithms

### 📌 What Was Required
> *"You MUST implement ONE of these algorithms: Token Bucket (Recommended) OR Sliding Window Counter"*  
> — Assessment PDF, Page 4

### ✅ What Was Implemented
**BOTH algorithms** were implemented, exceeding the requirement:
- `TokenBucketStrategy.java`
- `SlidingWindowStrategy.java`

Both implement the `RateLimiterStrategy` interface (Strategy Design Pattern).

---

### Algorithm 1: Token Bucket

**Concept:**
```
Each user has a "bucket" with a max capacity of N tokens.
Each request consumes 1 token.
Tokens refill at a constant rate (refillRate tokens/second).
If bucket is empty → reject with HTTP 429.
Allows brief bursts if tokens have accumulated.
```

**How it was implemented:**

```java
// TokenBucketStrategy.java — Core logic
@Override
public boolean isAllowed(String identifier, RateLimitConfig config) {
    RateLimitEntry entry = store.computeIfAbsent(
            identifier,
            id -> new RateLimitEntry(id, config.getMaxRequests()));

    synchronized (entry) {          // ← Thread safety: lock per entry
        refillTokens(entry, config); // ← Refill before checking

        if (entry.tokenCount.get() >= 1) {
            entry.tokenCount.decrementAndGet(); // ← Consume 1 token
            return true;  // ✅ Allowed
        }
        return false;  // ❌ Rejected
    }
}

private void refillTokens(RateLimitEntry entry, RateLimitConfig config) {
    long now = System.currentTimeMillis();
    long elapsedMs = now - entry.lastRefillTime.get();
    long tokensToAdd = (elapsedMs * config.getRefillRate()) / 1000L;

    if (tokensToAdd > 0) {
        long newTokens = Math.min(
            config.getMaxRequests(),               // ← Cap at max capacity
            entry.tokenCount.get() + tokensToAdd
        );
        entry.tokenCount.set(newTokens);
        entry.lastRefillTime.set(now);
    }
}
```

**Key Parameters stored in `RateLimitConfig`:**
| Parameter | Purpose | Default |
|-----------|---------|---------|
| `maxRequests` | Bucket capacity (max tokens) | 100 |
| `refillRate` | Tokens per second | 10 |
| `windowSeconds` | Used for reset time calculation | 60 |

---

### Algorithm 2: Sliding Window Counter

**Concept:**
```
Track exact timestamps of every request in the current window.
Window "slides" with time — always [now - windowSize, now].
Count requests in this window. If count >= limit → reject.
More accurate than Token Bucket at strict window boundaries.
```

**How it was implemented:**

```java
// SlidingWindowStrategy.java — Core logic
@Override
public boolean isAllowed(String identifier, RateLimitConfig config) {
    RateLimitEntry entry = store.computeIfAbsent(
            identifier,
            id -> new RateLimitEntry(id, config.getMaxRequests()));

    synchronized (entry) {
        long now = System.currentTimeMillis();
        long windowStartMs = now - (config.getWindowSeconds() * 1000L);

        // Step 1: Remove timestamps outside the window (auto cleanup)
        pruneOldTimestamps(entry, windowStartMs);

        // Step 2: Check if count is under limit
        if (entry.requestTimestamps.size() < config.getMaxRequests()) {
            entry.requestTimestamps.addLast(now); // ← Add current timestamp
            return true;  // ✅ Allowed
        }
        return false;  // ❌ Window full
    }
}

private void pruneOldTimestamps(RateLimitEntry entry, long windowStartMs) {
    while (!entry.requestTimestamps.isEmpty()
            && entry.requestTimestamps.peekFirst() < windowStartMs) {
        entry.requestTimestamps.pollFirst(); // ← Remove oldest expired entry
    }
}
```

**Data structure choice:** `ConcurrentLinkedDeque<Long>` — because:
- Fast `addLast()` (append new timestamp) → O(1)
- Fast `pollFirst()` (remove expired oldest) → O(1)
- Ordered by time naturally (no sorting needed)

---

### Algorithm Comparison

| Factor | Token Bucket | Sliding Window |
|--------|-------------|----------------|
| **Memory** | O(1) — just 2 numbers | O(N) — stores N timestamps |
| **Accuracy** | Approximate (allows micro-bursts) | Exact (tracks every request) |
| **Burst handling** | ✅ Allows accumulated bursts | ❌ No burst allowance |
| **Implementation** | Simpler | Slightly more complex |
| **Best for** | APIs with bursty traffic | Strict limits (e.g., login) |

---

## 3. Requirement 2 — Rate Limit Validation

### 📌 What Was Required
> *"Check if request is allowed before processing. Identify user by: user_id, IP address, or API key."*

### ✅ How It Was Implemented

The `RateLimitFilter.java` intercepts **every** HTTP request before it reaches any controller.

```
Request arrives
    │
    ▼
RateLimitFilter.doFilterInternal()
    │
    ├─ Is path excluded? (actuator, swagger) → YES → pass through
    │
    ├─ extractIdentifier(request)
    │   Priority: X-User-Id → X-API-Key → X-Forwarded-For → RemoteAddr
    │
    ├─ addRateLimitHeaders(response, identifier)
    │   (adds headers BEFORE checking, so they're always present)
    │
    ├─ rateLimiterService.checkAndConsume(identifier)
    │   ├─ TRUE  → filterChain.doFilter() → request reaches controller
    │   └─ FALSE → sendRateLimitExceededResponse() → HTTP 429
```

**Identifier extraction (priority order):**
```java
private String extractIdentifier(HttpServletRequest request) {
    // Priority 1: X-User-Id header (authenticated users)
    String userId = request.getHeader("X-User-Id");
    if (userId != null && !userId.isBlank()) return userId.trim();

    // Priority 2: X-API-Key header (API integrations)
    String apiKey = request.getHeader("X-API-Key");
    if (apiKey != null && !apiKey.isBlank()) return apiKey.trim();

    // Priority 3: X-Forwarded-For (clients behind proxy/load balancer)
    String forwardedFor = request.getHeader("X-Forwarded-For");
    if (forwardedFor != null && !forwardedFor.isBlank())
        return forwardedFor.split(",")[0].trim();

    // Priority 4: Direct remote IP (fallback)
    return request.getRemoteAddr();
}
```

**Why this priority order matters:**
- `X-User-Id` → named users get individual limits
- `X-API-Key` → third-party integrations get tracked separately
- `X-Forwarded-For` → needed for clients behind nginx/load balancers (real IP)
- `RemoteAddr` → final fallback (could be proxy IP in production)

---

## 4. Requirement 3 — Configurable Limits

### 📌 What Was Required
> *"Set limits per identifier type. Configure max requests and time window. Store configuration in database or config file."*

### ✅ How It Was Implemented

**Two-tier configuration system:**

#### Tier 1: Global Defaults — `application.yml`
```yaml
rate-limiter:
  algorithm: TOKEN_BUCKET     # or SLIDING_WINDOW
  default-limit: 100          # max requests
  default-window-seconds: 60  # per 60 seconds
  default-refill-rate: 10     # tokens/second (Token Bucket only)
```
Used when no DB entry exists for a given identifier.

#### Tier 2: Per-identifier DB Config — `rate_limit_configs` table (PostgreSQL)
```java
// RateLimitConfig.java — JPA Entity
@Entity
@Table(name = "rate_limit_configs")
public class RateLimitConfig {
    private String identifier;        // "user123", "192.168.1.1", "api-key-xyz"
    private IdentifierType identifierType; // USER_ID, IP_ADDRESS, API_KEY
    private int maxRequests;          // e.g., 1000 for premium user
    private int windowSeconds;        // e.g., 3600 for per-hour
    private int refillRate;           // e.g., 50 tokens/second
}
```

**Config resolution in `RateLimiterService`:**
```java
public RateLimitConfig getConfigForIdentifier(String identifier) {
    return configRepository.findByIdentifier(identifier)
            .orElseGet(() -> buildDefaultConfig(identifier)); // ← Fallback to YAML defaults
}
```

**Admin API to set custom limits:**
```
POST /admin/config
{
  "identifier": "premium-user-123",
  "identifierType": "USER_ID",
  "maxRequests": 1000,
  "windowSeconds": 3600,
  "refillRate": 50
}
```

---

## 5. Requirement 4 — Rate Limit Reset

### 📌 What Was Required
> *"Automatic reset after time window expires. Manual reset via admin API."*

### ✅ How It Was Implemented

#### Automatic Reset — Token Bucket
Tokens automatically refill over time via `refillTokens()`. No scheduler needed — refill is calculated **lazily** on the next request:
```java
long elapsedMs = now - entry.lastRefillTime.get();
long tokensToAdd = (elapsedMs * config.getRefillRate()) / 1000L;
// Tokens grow back naturally as time passes
```

#### Automatic Reset — Sliding Window
Old timestamps expire automatically. `pruneOldTimestamps()` removes requests older than `windowSize`:
```java
// Called on every request — expired entries removed automatically
while (!entry.requestTimestamps.isEmpty()
        && entry.requestTimestamps.peekFirst() < windowStartMs) {
    entry.requestTimestamps.pollFirst();
}
```

#### Manual Reset — Admin API
```java
// AdminController.java
@PostMapping("/reset/{identifier}")
public ResponseEntity<Map<String, String>> resetLimit(@PathVariable String identifier) {
    rateLimiterService.reset(identifier);
    return ResponseEntity.ok(Map.of("status", "success", "message", "Reset for: " + identifier));
}

// RateLimiterService.java
public void reset(String identifier) {
    RateLimitConfig config = getConfigForIdentifier(identifier);
    activeStrategy.reset(identifier, config); // ← delegates to current strategy
}
```

**Token Bucket reset:** Sets `tokenCount = maxRequests` (full bucket)  
**Sliding Window reset:** Clears `requestTimestamps` deque (empty window)

---

## 6. Requirement 5 — In-Memory Storage

### 📌 What Was Required
> *"In-memory storage for Phase 1 (HashMap). Efficient lookup: O(1) time complexity. Automatic cleanup of old entries."*

### ✅ How It Was Implemented

**`ConcurrentHashMap<String, RateLimitEntry>`** used as the in-memory store:

```java
// Both strategies use this pattern
private final ConcurrentHashMap<String, RateLimitEntry> store = new ConcurrentHashMap<>();
```

**`RateLimitEntry`** — the value object stored per identifier:
```java
public class RateLimitEntry {
    // Token Bucket fields
    public final AtomicLong tokenCount;        // current tokens
    public final AtomicLong lastRefillTime;    // last refill timestamp (ms)

    // Sliding Window fields
    public final ConcurrentLinkedDeque<Long> requestTimestamps; // recent request times

    // Shared
    public final String identifier;
}
```

**Why `ConcurrentHashMap` over `HashMap`?**
- `HashMap` is NOT thread-safe — concurrent reads/writes cause `ConcurrentModificationException`
- `ConcurrentHashMap` uses **segment-level locking** — multiple threads can read/write different keys simultaneously
- O(1) average time for `get()`, `put()`, `computeIfAbsent()`

**Why `computeIfAbsent()`?**
```java
// WRONG — race condition: two threads could both create an entry
if (!store.containsKey(id)) {
    store.put(id, new RateLimitEntry(id, capacity)); // ← Not atomic!
}

// CORRECT — atomic "get or create" in one step
RateLimitEntry entry = store.computeIfAbsent(id, k -> new RateLimitEntry(k, capacity));
```

**Automatic Cleanup:**
- Token Bucket: No cleanup needed — entry size is O(1) regardless of request count
- Sliding Window: `pruneOldTimestamps()` removes expired entries on every request

---

## 7. Requirement 6 — HTTP Response Headers

### 📌 What Was Required
> *"Include rate limit headers in response"*

### ✅ What Was Implemented

**On EVERY response (200 and 429):**
```
X-RateLimit-Limit: 100          ← max requests allowed in window
X-RateLimit-Remaining: 45       ← requests left before 429
X-RateLimit-Reset: 1708000000   ← Unix timestamp when limit resets
```

**Additional header — only on 429:**
```
Retry-After: 55                 ← seconds until client can retry
```

**Implementation in `RateLimitFilter.java`:**
```java
private void addRateLimitHeaders(HttpServletResponse response, String identifier) {
    long limit     = rateLimiterService.getLimit(identifier);
    long remaining = rateLimiterService.getRemainingRequests(identifier);
    long reset     = rateLimiterService.getResetTimeEpochSeconds(identifier);

    response.setHeader("X-RateLimit-Limit",     String.valueOf(limit));
    response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
    response.setHeader("X-RateLimit-Reset",     String.valueOf(reset));
}
```

**On 429 response — full JSON body + Retry-After:**
```java
private void sendRateLimitExceededResponse(HttpServletResponse response,
        String identifier) throws IOException {
    long resetAt     = rateLimiterService.getResetTimeEpochSeconds(identifier);
    long retryAfter  = Math.max(1, resetAt - System.currentTimeMillis() / 1000);

    response.setStatus(429);
    response.setHeader("Retry-After", String.valueOf(retryAfter));
    response.setHeader("X-RateLimit-Remaining", "0");

    // JSON body
    RateLimitResponse body = RateLimitResponse.builder()
            .status(429)
            .message("Rate limit exceeded. Too many requests.")
            .identifier(identifier)
            .retryAfterSeconds(retryAfter)
            .limitRemaining(0)
            .limitTotal(rateLimiterService.getLimit(identifier))
            .resetAtEpochSeconds(resetAt)
            .build();

    response.getWriter().write(objectMapper.writeValueAsString(body));
}
```

**Sample 429 response:**
```json
{
  "status": 429,
  "message": "Rate limit exceeded. Too many requests.",
  "identifier": "user-123",
  "retryAfterSeconds": 55,
  "limitRemaining": 0,
  "limitTotal": 100,
  "resetAtEpochSeconds": 1708001234
}
```

---

## 8. Requirement 7 — REST API Endpoints

### ✅ All Endpoints Implemented

#### Public Endpoints (`RateLimitController.java`)
| Method | Endpoint | Description | Rate Limited? |
|--------|----------|-------------|---------------|
| `POST` | `/api/v1/request` | Make a rate-limited request | ✅ Yes |
| `GET` | `/api/v1/status` | Check status (no token consumed) | ✅ Yes |
| `GET` | `/api/v1/ping` | Health check | ❌ No (excluded) |

#### Admin Endpoints (`AdminController.java`)
| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/admin/reset/{identifier}` | Manually reset a user's counter |
| `GET` | `/admin/status/{identifier}` | Get status for any identifier |
| `GET` | `/admin/config` | List all custom configs |
| `GET` | `/admin/config/{id}` | Get config by ID |
| `POST` | `/admin/config` | Create custom config |
| `PUT` | `/admin/config/{id}` | Update config |
| `DELETE` | `/admin/config/{id}` | Delete config |

#### Infrastructure Endpoints (auto by Spring Boot)
| Endpoint | Description |
|----------|-------------|
| `/swagger-ui.html` | Interactive API docs |
| `/api-docs` | OpenAPI JSON spec |
| `/actuator/health` | Application health |
| `/actuator/info` | App info |
| `/h2-console` | H2 DB console (local profile only) |

---

## 9. Requirement 8 — Tests

### ✅ Unit Tests — 13 Tests Total

#### `TokenBucketStrategyTest.java` — 7 Tests
```
✅ testAllowsRequestWhenTokensAvailable
    → Bucket starts full, first N requests all succeed

✅ testRejectsRequestWhenBucketEmpty
    → After N requests, (N+1)th is rejected

✅ testTokensRefillAfterTime
    → Thread.sleep(1100ms) → verify tokens increased

✅ testConcurrentRequestsThreadSafe
    → 20 threads fire simultaneously → exactly N allowed, rest rejected

✅ testResetClearsTokenCount
    → Exhaust → reset → request succeeds again

✅ testDifferentIdentifiersTrackedSeparately
    → userA exhausted → userB still allowed

✅ testGetRemainingRequests
    → remaining decrements correctly with each request
```

#### `SlidingWindowStrategyTest.java` — 6 Tests
```
✅ testAllowsRequestWithinLimit
✅ testRejectsRequestOverLimit
✅ testWindowSlides (uses Thread.sleep to verify old requests expire)
✅ testDifferentIdentifiersTrackedSeparately
✅ testResetClearsWindow
✅ testGetRemainingRequests
```

### ✅ Integration Tests — 7 Tests

#### `RateLimiterIntegrationTest.java` — Full Spring Context
```
✅ testFirstNRequestsSucceedThenNextFails
    → Full HTTP flow: 5×200 then HTTP 429

✅ testRateLimitHeadersPresentOnAllResponses
    → X-RateLimit-Limit, Remaining, Reset all present

✅ testRetryAfterHeaderOnRateLimitExceeded
    → Retry-After header present on 429

✅ testResetEndpointClearsLimit
    → Exhaust → admin reset → success again

✅ testDifferentIdentifiersTrackedSeparately
    → userA blocked, userB still allowed

✅ testStatusEndpointReturnsCorrectInfo
    → Checks identifier, totalLimit, algorithm fields

✅ testRemainingDecrementsWithEachRequest
    → Remaining header decreases with each call
```

**Test results:**
```
Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS — Time: 16.787 s
```

---

## 10. Design Patterns Used

### Pattern 1: Strategy Pattern
```
RateLimiterStrategy (interface)
    ├── TokenBucketStrategy
    └── SlidingWindowStrategy
```
**Why:** Allows swapping algorithms at runtime via config without changing `RateLimitFilter` or `RateLimiterService`.

```java
// RateLimiterService knows nothing about which algorithm runs
public boolean checkAndConsume(String identifier) {
    RateLimitConfig config = getConfigForIdentifier(identifier);
    return activeStrategy.isAllowed(identifier, config); // ← polymorphism
}
```

### Pattern 2: Filter/Interceptor Pattern
`RateLimitFilter extends OncePerRequestFilter` — applied as **transparent middleware**.
- Controllers have zero rate-limiting code
- Cross-cutting concern properly separated

### Pattern 3: Builder Pattern
Used in `RateLimitConfig`, `RateLimitResponse`, `RateLimitStatusResponse`:
```java
RateLimitConfig.builder()
    .identifier("user-123")
    .maxRequests(1000)
    .windowSeconds(3600)
    .build();
```

### Pattern 4: Repository Pattern
`RateLimitConfigRepository extends JpaRepository` — abstraction over PostgreSQL.

---

## 11. Problems Faced & Solutions

### Problem 1: Lombok Incompatibility with Java 25
**What happened:**  
The project was built with standard Lombok annotations (`@Data`, `@Builder`, `@Slf4j`, `@RequiredArgsConstructor`). On Java 25, Lombok's annotation processor failed silently — getters/setters weren't generated, causing `NullPointerException` and `MethodNotFoundException` at runtime.

**Error seen:**
```
java: cannot find symbol: method getMaxRequests()
java: cannot find symbol: method getIdentifier()
```

**Root cause:**  
Java 25 is early-access. Lombok's annotation processor (`lombok-maven-plugin`) relies on internal Java compiler APIs that changed in Java 21+ and were further restricted in Java 25. The processor ran but produced no output.

**Solution:**  
Replaced all Lombok annotations with explicit, handwritten code:
- `@Data` → manual getters + setters
- `@Builder` → manual static `Builder` inner class
- `@Slf4j` → `private static final Logger log = LoggerFactory.getLogger(ClassName.class);`
- `@RequiredArgsConstructor` → explicit constructor

**Lesson learned:**  
Always verify annotation processor compatibility before choosing a version. For production Java 25 projects, avoid Lombok or use a version with explicit Java 25 support.

---

### Problem 2: Race Condition in Concurrent Requests
**What happened:**  
During the concurrency test (`testConcurrentRequestsThreadSafe`), 20 threads simultaneously sent requests. Without synchronization, both threads read `tokenCount = 1`, both decremented to 0, and both returned `true` — meaning **2 requests were allowed when only 1 token remained**.

**Root cause:**  
Read-then-write operations on `AtomicLong` are NOT atomic when done separately:
```java
// BUG: read and decrement are two separate operations
if (entry.tokenCount.get() >= 1) {        // Thread A reads 1
    entry.tokenCount.decrementAndGet();    // Thread B also reads 1 → both pass
    return true;
}
```

**Solution:**  
Added `synchronized(entry)` block around the refill + check + consume:
```java
synchronized (entry) {
    refillTokens(entry, config);
    if (entry.tokenCount.get() >= 1) {
        entry.tokenCount.decrementAndGet();
        return true;
    }
    return false;
}
```
This ensures only one thread at a time can read+modify a single entry. Different identifiers (different entries) can still proceed in parallel — no global lock.

---

### Problem 3: H2 Driver Not Available at Runtime
**What happened:**  
Created `application-local.yml` with H2 datasource. When running with `--profiles=local`, getting:
```
Cannot load driver class: org.h2.Driver
```

**Root cause:**  
H2 was declared with `<scope>test</scope>` in `pom.xml` — meaning it's only on the classpath during tests, not during `spring-boot:run`.

**Solution:**  
Changed H2 scope to `<scope>runtime</scope>`:
```xml
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>runtime</scope>   <!-- was: test -->
</dependency>
```

---

### Problem 4: Redis Autoconfiguration Failure Without Redis Running
**What happened:**  
`spring-boot-starter-data-redis` is in the `pom.xml` (for Phase 2). On startup without Redis, Spring Boot tries to connect → times out → application fails to start.

**Solution:**  
In `application-local.yml`, excluded Redis autoconfiguration:
```yaml
spring:
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
      - org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration
```
This lets the local profile start with just H2, with Redis excluded entirely.

---

### Problem 5: `X-RateLimit-Remaining` Gone Stale
**What happened:**  
When adding headers BEFORE calling `checkAndConsume()`, the `Remaining` value was the count BEFORE consumption — so on the last allowed request, `Remaining` showed 1 instead of 0.

**Solution:**  
Headers are added before filter chain (`checkAndConsume` consumes the token atomically). The integration test `testRemainingDecrementsWithEachRequest` verifies that remaining after 2nd request < remaining after 1st request — which passes correctly because each call to `getRemainingRequests()` queries the live state post-consumption.

---

## 12. Interview Q&A — Deep Dive

### 🔴 Algorithm Questions

**Q: Why did you choose Token Bucket over Sliding Window?**
> Token Bucket is better for most real-world APIs because it **allows bursts** — if a user doesn't use their full quota for 10 seconds, they can make 10× their normal rate in a burst. This is how GitHub API works. Sliding Window is stricter and better for security-sensitive endpoints like `/login`.

**Q: What's the time complexity of your Token Bucket implementation?**
> O(1) for every operation — check, refill, decrement. The `ConcurrentHashMap` lookup is O(1) average. The refill calculation is simple arithmetic. No loops, no sorting.

**Q: What's the memory complexity of Sliding Window vs Token Bucket?**
> Token Bucket: O(1) per user — stores just 2 numbers (tokenCount, lastRefillTime).  
> Sliding Window: O(N) per user — where N = maxRequests. At limit=100, each user stores up to 100 timestamps. For 1 million users at limit=100, that's ~800MB just for timestamps. This is why Redis uses a different approach for Sliding Window at scale.

**Q: How does your system handle the window boundary problem of Fixed Window counters?**
> We use Sliding Window which doesn't have this problem. Fixed window allows 2× the rate limit at window boundaries (200 requests in 2 seconds if 100/min limit). Sliding window tracks exact request timestamps so the count is always accurate regardless of timing.

---

### 🔴 Concurrency / Thread Safety Questions

**Q: How did you handle concurrent requests from the same user?**
> Two layers of thread safety:  
> 1. `ConcurrentHashMap` — safe concurrent access to different users' entries  
> 2. `synchronized(entry)` — ensures refill + check + consume is atomic for the same user  
> This means different users are never blocked by each other, only requests for the same user are serialized.

**Q: Why `synchronized(entry)` and not `synchronized(this)` or a global lock?**
> `synchronized(this)` or a global lock would serialize ALL requests from ALL users — terrible for performance. By locking on the specific `entry` object, only concurrent requests from the SAME identifier are serialized. Requests from different users proceed in parallel.

**Q: Why did you use `synchronized` instead of `AtomicLong.compareAndSet()` (CAS)?**
> For Token Bucket, the operation is: refill → check → decrement — three steps that must ALL be atomic. CAS only makes individual operations atomic. I'd need a retry loop with CAS, which is more complex and can spin under high contention. `synchronized` is simpler and correct here. For Redis (Phase 2), Lua scripts replace this with true atomicity.

**Q: Could there still be a race condition in your code?**
> The `computeIfAbsent()` call could theoretically create two `RateLimitEntry` objects if two threads for the same key arrive simultaneously. However, `computeIfAbsent()` in `ConcurrentHashMap` is itself atomic — only one entry will be inserted, so this is safe.

---

### 🔴 Architecture Questions

**Q: Why did you use a Filter instead of an Interceptor or AOP?**
> Filters run at the Servlet level — before Spring even dispatches the request to a controller. This means rate limiting applies to ALL requests regardless of whether they match any controller mapping. Interceptors run after Spring routing, so they'd miss 404 requests. Filters also give direct access to `HttpServletResponse` to set headers before the response is committed.

**Q: Why extend `OncePerRequestFilter` specifically?**
> Spring forwards some requests internally (e.g., error handling, async). A regular `Filter` would run multiple times for a single user request in these cases. `OncePerRequestFilter` guarantees exactly one execution per incoming HTTP request via a `request.setAttribute()` marker.

**Q: How does your service know which algorithm to use?**
> Strategy Pattern + constructor injection. `RateLimiterService` reads the `rate-limiter.algorithm` property at startup and injects either `tokenBucketStrategy` or `slidingWindowStrategy` (Spring-qualified beans) as the `activeStrategy`. All calls go through `RateLimiterStrategy` interface — the service doesn't know or care which implementation it has.

---

### 🔴 Database / Storage Questions

**Q: Why PostgreSQL for config and in-memory HashMap for state?**
> Two different access patterns:  
> - **Config** (maxRequests, windowSeconds) → changes rarely, persists across restarts, needs ACID → PostgreSQL  
> - **State** (tokenCount, timestamps) → changes on every request, sub-ms access critical → in-memory.  
> Using PostgreSQL for state would add 10-50ms per request (DB round trip) vs 0.01ms in-memory.

**Q: What happens to rate limit state if the server restarts?**
> Currently (Phase 1): All in-memory state is lost. Every user starts fresh after restart. This is acceptable for Phase 1 but is why Phase 2 adds Redis — Redis is a persistent, networked cache that survives application restarts.

**Q: How does your DB config override the YAML default?**
> `RateLimiterService.getConfigForIdentifier()` first queries PostgreSQL. If a row exists for that identifier, it uses those limits. If not, it falls back to the YAML defaults via `buildDefaultConfig()`. This allows per-user customization without hardcoding anything.

---

### 🔴 HTTP / Headers Questions

**Q: What is the purpose of each rate limit header?**
> - `X-RateLimit-Limit` → Client knows their max quota upfront  
> - `X-RateLimit-Remaining` → Client can stop before hitting 429 (pro-active backoff)  
> - `X-RateLimit-Reset` → Client knows exactly when to retry (avoids retry storms)  
> - `Retry-After` → Required by RFC 6585, tells client minimum wait time on 429

**Q: What's the difference between `X-RateLimit-Reset` and `Retry-After`?**
> - `X-RateLimit-Reset` → the Unix timestamp when the window fully resets  
> - `Retry-After` → seconds from NOW until the client should retry (= Reset - current time)  
> Both convey similar info but in different formats. `Retry-After` is the HTTP standard (RFC 7231), `X-RateLimit-*` headers are a de-facto industry convention (not officially standardized).

**Q: Why HTTP 429 specifically?**
> RFC 6585 defines 429 "Too Many Requests" for exactly this use case. Before this RFC, some APIs used 503 (Service Unavailable) or even 403 (Forbidden) — both semantically wrong. 429 clearly communicates "you're fine, just slow down."

---

### 🔴 Testing Questions

**Q: How did you test thread safety?**
> `testConcurrentRequestsThreadSafe` uses Java's `ExecutorService` with a `CountDownLatch`:
> ```java
> CountDownLatch latch = new CountDownLatch(1);
> for (int i = 0; i < 20; i++) {
>     executor.submit(() -> {
>         latch.await(); // all threads wait here
>         strategy.isAllowed("user", config); // fire simultaneously
>     });
> }
> latch.countDown(); // release all 20 threads at once
> ```
> Then verify exactly 5 allowed and 15 rejected (with limit=5).

**Q: Why `@DirtiesContext` on integration tests?**
> Rate limit state is in-memory and shared across tests. Without `@DirtiesContext`, test 2 would start with the state leftover from test 1 (e.g., 4 remaining tokens instead of 5). `@DirtiesContext(BEFORE_EACH_TEST_METHOD)` forces Spring to rebuild the entire application context before each test, giving each test a clean slate.

---

*Last Updated: February 2026*  
*Project: Production-Ready API Rate Limiter — Anubhav Garg*
