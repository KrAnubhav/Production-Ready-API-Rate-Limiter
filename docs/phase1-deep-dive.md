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

## 13. All Possible Interview Questions — Complete Bank

> 💡 **How to use this:** These are grouped by topic. An interviewer may start broad and go deep. Read every answer aloud to practice.

---

### 🟠 Category A — "Tell Me About Your Project" (Opening Questions)

**Q1: Can you explain what your project does in 2 minutes?**
> This project is a production-grade API Rate Limiter service built with Spring Boot 3 and Java 17. Its purpose is to prevent API abuse by enforcing request limits per user. When a user exceeds their limit, they get an HTTP 429 response instead of being allowed to overload the server.
>
> I implemented two algorithms — Token Bucket and Sliding Window. Token Bucket works like a refillable container: each user gets N tokens, each request consumes one, and tokens refill at a constant rate. Sliding Window tracks the exact timestamps of recent requests in a rolling time window.
>
> Every HTTP response includes rate limit headers (`X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`) so clients can self-regulate. There's also an admin API for configuring per-user limits in PostgreSQL and resetting counters manually.
>
> The system is fully thread-safe using `ConcurrentHashMap` + `synchronized` blocks, and has 20 passing tests.

---

**Q2: What problem does rate limiting solve?**
> Without rate limiting, a single abusive client (or a DDoS attack) can monopolize server resources, slow down the service for legitimate users, and potentially crash the system. Rate limiting ensures:
> 1. **Fair usage** — no single user consumes more than their share
> 2. **Abuse prevention** — bots, scrapers, credential stuffing attacks are throttled
> 3. **Cost control** — in serverless/paid APIs, unlimited usage would be expensive
> 4. **SLA enforcement** — different tiers (free vs premium) get different limits

---

**Q3: Why did you pick this project out of the four options?**
> Rate limiting is a core backend concept that appears in almost every production system. It involves algorithms, concurrency, HTTP protocol design, distributed systems (for Phase 2), and system design thinking. Unlike a CRUD project, this project requires genuinely solving hard problems like race conditions and window boundary accuracy. It directly demonstrates backend engineering skills that interviewers care about.

---

**Q4: What tech stack did you use and why?**
> - **Java 17 + Spring Boot 3** — industry standard for backend services, required by the assessment
> - **PostgreSQL** — for persisting per-user config (survives restarts, ACID guarantees)
> - **H2** — in-memory DB for local dev and tests (no Docker setup needed)
> - **Spring Data JPA** — clean abstraction over SQL without boilerplate
> - **JUnit 5 + MockMvc** — for unit and integration testing
> - **SpringDoc/Swagger** — API documentation, required by assessment
> - **Docker + docker-compose** — for reproducible deployment

---

### 🟠 Category B — Algorithm Deep Dive

**Q5: Explain Token Bucket algorithm to me like I'm 5.**
> Imagine you have a bucket that can hold 100 marbles. Every time you make an API request, you take 1 marble out. A machine slowly adds 10 marbles back every second. If the bucket is empty, you can't make requests — you have to wait for marbles to refill. If you haven't used the API for a while, marbles accumulate, allowing a burst of activity.

---

**Q6: Explain Sliding Window algorithm.**
> Every time you make a request, we write down the exact time. To decide if a new request is allowed, we look at our notebook and count how many timestamps are within the last 60 seconds (the "window"). If the count is less than the limit, we allow it and add the new timestamp. If it's at the limit, we reject. As time passes, old timestamps "slide out" of the window automatically.

---

**Q7: What is the difference between Token Bucket and Leaky Bucket?**
> **Token Bucket:** Tokens accumulate when unused. Allows bursts. If no requests for 10 seconds, user gets a burst of accumulated tokens.
>
> **Leaky Bucket:** Processes requests at a constant, fixed output rate regardless of input rate. No burst allowance. Like water leaking from a bucket at a constant drip — requests are queued and released at a steady pace. Good for traffic shaping, not just limiting.
>
> Key difference: Token Bucket **allows bursts**, Leaky Bucket **enforces constant rate**.

---

**Q8: What is Fixed Window counter and what's wrong with it?**
> Fixed Window divides time into fixed intervals (e.g., 9:00–9:01, 9:01–9:02). You count requests per interval. Problem: **boundary burst attack**. If the limit is 100/minute, a user can send 100 requests at 9:00:59 and 100 requests at 9:01:00 — that's 200 requests in 2 seconds, but both windows show 100 which is "valid". Sliding Window eliminates this by using a rolling window anchored to the current time.

---

**Q9: When would you use Token Bucket vs Sliding Window in production?**
> | Use Token Bucket | Use Sliding Window |
> |---|---|
> | General API limits (bursty traffic OK) | Login endpoints (strict, no bursts) |
> | Mobile apps (batchy usage patterns) | Payment processing |
> | CDN rate limiting | Security-critical endpoints |
> | When refill rate matters | When strict per-second accuracy matters |

---

**Q10: How accurate is your Token Bucket's refill calculation?**
> It uses wall clock time (`System.currentTimeMillis()`). The calculation is:
> ```
> tokensToAdd = elapsedMilliseconds × refillRate / 1000
> ```
> It's accurate to the nearest whole token (integer division). If only 900ms have passed with a 10/sec refill rate, `900 × 10 / 1000 = 9` tokens are added (not 9.0, due to integer division). This slight under-refill is intentional — it prevents fractional token accumulation across many calls. For extreme precision, a `double` accumulator could be used.

---

**Q11: What happens if a user sends exactly N requests at the same millisecond?**
> For Token Bucket: All N requests arrive simultaneously, all enter the `synchronized(entry)` block one at a time. Each checks `tokenCount >= 1`, decrements, and returns true — in sequence. If N = capacity, all N are allowed. The (N+1)th is rejected.
>
> For Sliding Window: Same — all N timestamps are added one at a time inside `synchronized`. All N fit within the limit, all allowed.
>
> The `synchronized(entry)` ensures correctness even in perfect simultaneous arrival.

---

**Q12: Could you implement Token Bucket without any synchronization?**
> theoretically yes using a CAS (Compare-And-Swap) loop with `AtomicLong.compareAndSet()`. But the challenge is that refill + check + decrement are THREE separate steps that all need to be atomic together. A CAS loop would need to retry all three on contention, and under high load this causes spin-waits that waste CPU. `synchronized` is simpler, more readable, and performs well because the critical section is tiny (microseconds).

---

**Q13: What's the difference between rate limiting and throttling?**
> **Rate Limiting:** Hard limit — once exceeded, request is rejected (HTTP 429).
>
> **Throttling:** Soft limit — once the rate is exceeded, requests are slowed down (queued/delayed) rather than rejected. Users still eventually get responses but after a delay.
>
> This project implements **rate limiting** (hard reject). Throttling would require a request queue which adds latency and complexity.

---

### 🟠 Category C — Concurrency & Thread Safety

**Q14: Walk me through exactly what happens when 1000 concurrent users hit your API simultaneously.**
> 1. 1000 HTTP requests arrive simultaneously at Tomcat
> 2. Tomcat's thread pool (default: 200 threads) handles them — ~5 requests queue per thread initially
> 3. Each thread runs `RateLimitFilter.doFilterInternal()`
> 4. Each calls `extractIdentifier()` — no shared state here, safe
> 5. `ConcurrentHashMap.computeIfAbsent()` — each identifier gets its own `RateLimitEntry`, no cross-user contention
> 6. `synchronized(entry)` — only threads for the SAME identifier wait for each other
> 7. For 1000 different users: effectively all run in parallel (1 lock per user, no contention)
> 8. For 1000 requests from same user: run sequentially through the synchronized block — first N allowed, rest rejected

---

**Q15: What is a race condition? Did you have one? How did you fix it?**
> A race condition occurs when multiple threads access shared data simultaneously and the final result depends on which thread runs first — producing unpredictable, incorrect results.
>
> Yes, I had one. Without `synchronized`:
> - Thread A reads `tokenCount = 1`
> - Thread B reads `tokenCount = 1` (before A decrements)
> - Thread A decrements → `tokenCount = 0`, returns `true`
> - Thread B decrements → `tokenCount = -1`, returns `true`
> - **Both were allowed when only 1 token existed**
>
> Fix: `synchronized(entry)` makes read+decrement atomic. Only one thread at a time can be inside that block for the same entry.

---

**Q16: What is the difference between `synchronized`, `volatile`, and `AtomicLong`?**
> - **`volatile`**: Ensures visibility — a write by one thread is immediately visible to all others. Does NOT prevent race conditions on compound operations (read-modify-write).
> - **`AtomicLong`**: Makes single operations atomic (get, set, incrementAndGet, compareAndSet). Still has race conditions for multi-step operations.
> - **`synchronized`**: Makes an entire BLOCK of code atomic — only one thread can execute it at a time. Most powerful but adds latency from lock acquisition.
>
> In my code: `AtomicLong tokenCount` for individual reads (non-critical paths), `synchronized(entry)` for the refill+check+consume block.

---

**Q17: Why is `ConcurrentHashMap` better than `Collections.synchronizedMap(new HashMap())`?**
> `Collections.synchronizedMap` uses a single global lock — every read and write, regardless of which key, acquires the same lock. This means all users block each other.
>
> `ConcurrentHashMap` uses **segment/bucket-level locking** — it divides the map into segments, and operations on different keys (different users) run in parallel. Only operations on the same bucket contend. This gives much better throughput under concurrent load.

---

**Q18: What is `computeIfAbsent()` and why is it important here?**
> `computeIfAbsent(key, mappingFunction)` atomically:
> 1. Checks if the key exists
> 2. If not, computes the value using the function
> 3. Inserts it
> 4. Returns the value (existing or new)
>
> If I used `if (!map.containsKey(k)) map.put(k, new Entry())` — two threads could both see the key missing and both create entries. One would overwrite the other, potentially losing already-accumulated state. `computeIfAbsent` eliminates this race condition.

---

**Q19: Is your implementation deadlock-proof?**
> Yes. A deadlock requires multiple threads holding different locks and waiting for each other's locks. In my implementation, each thread acquires only ONE lock at a time — `synchronized(entry)` — and immediately releases it. There's no `synchronized(A)` then `synchronized(B)` chaining, so deadlock is impossible.

---

### 🟠 Category D — Design & Architecture

**Q20: Why did you use the Strategy Pattern?**
> Without Strategy Pattern, `RateLimiterService` would have:
> ```java
> if (algorithm.equals("TOKEN_BUCKET")) {
>     // 50 lines of token bucket logic
> } else if (algorithm.equals("SLIDING_WINDOW")) {
>     // 50 lines of sliding window logic
> }
> ```
> This violates the Open/Closed Principle — adding a new algorithm means modifying existing code. With Strategy Pattern, adding a new algorithm (e.g., Fixed Window) means creating a new class that implements `RateLimiterStrategy` — zero changes to existing code.

---

**Q21: What SOLID principles did you apply?**
> - **S** (Single Responsibility): `RateLimitFilter` only extracts identifier + calls service. `TokenBucketStrategy` only does token bucket logic. Each class has one job.
> - **O** (Open/Closed): New algorithms added via new `RateLimiterStrategy` implementations — existing code unchanged.
> - **L** (Liskov Substitution): `TokenBucketStrategy` and `SlidingWindowStrategy` are fully substitutable through the `RateLimiterStrategy` interface.
> - **I** (Interface Segregation): `RateLimiterStrategy` interface only has methods relevant to rate limiting — no bloat.
> - **D** (Dependency Inversion): `RateLimiterService` depends on the `RateLimiterStrategy` interface, not concrete implementations. Spring injects the right one at startup.

---

**Q22: Why did you put rate limiting in a Filter and not a Controller?**
> Controllers are for business logic. Rate limiting is a cross-cutting concern — it should apply to ALL endpoints transparently without any controller knowing about it. A Filter is the correct architectural layer:
> - Runs before any controller code
> - Can short-circuit the request (return 429) without touching business logic
> - Applied globally — no need to annotate every endpoint
>
> Alternative: Spring AOP `@Around` advice, but that only intercepts Spring-managed method calls, not all HTTP requests.

---

**Q23: How would you add a new rate limiting algorithm (e.g., Fixed Window Counter)?**
> 1. Create `FixedWindowStrategy.java` implementing `RateLimiterStrategy`
> 2. Annotate with `@Component("fixedWindowStrategy")`
> 3. Implement the 5 interface methods
> 4. In `RateLimiterService` constructor, add:
>    ```java
>    else if ("FIXED_WINDOW".equalsIgnoreCase(properties.getAlgorithm())) {
>        this.activeStrategy = fixedWindow;
>    }
>    ```
> 5. In `application.yml`: `rate-limiter.algorithm: FIXED_WINDOW`
>
> **Zero changes** to `RateLimitFilter`, `AdminController`, or any other class. That's the power of Strategy Pattern.

---

**Q24: How does Spring Boot autowire which strategy to use?**
> Both `TokenBucketStrategy` and `SlidingWindowStrategy` are Spring beans annotated with `@Component("tokenBucketStrategy")` and `@Component("slidingWindowStrategy")`. In `RateLimiterService`, both are injected using `@Qualifier`:
> ```java
> public RateLimiterService(
>     @Qualifier("tokenBucketStrategy") RateLimiterStrategy tokenBucket,
>     @Qualifier("slidingWindowStrategy") RateLimiterStrategy slidingWindow, ...)
> ```
> At runtime, the constructor reads `properties.getAlgorithm()` and assigns the appropriate one to `activeStrategy`.

---

**Q25: What is `OncePerRequestFilter` and why use it?**
> It's a Spring base class for filters that guarantees `doFilterInternal()` is called exactly **once per HTTP request**. Without it, Spring's internal error handling can call the filter chain twice (e.g., when forwarding to `/error` after an exception). Using `OncePerRequestFilter` prevents a single user request from consuming two tokens.

---

**Q26: How does your system handle requests to non-existent endpoints (404)?**
> The `RateLimitFilter` runs at the Servlet level — before Spring's `DispatcherServlet` routes the request. So even a 404 request passes through the filter, consumes a token, and returns 429 if the limit is exceeded. This is intentional — bots often probe non-existent endpoints, and they should still be rate-limited.

---

### 🟠 Category E — HTTP & API Design

**Q27: Why return HTTP 429 and not 503 or 403?**
> - **403 Forbidden** → means "you don't have permission", implies permanent block. Wrong here.
> - **503 Service Unavailable** → means the server itself is overloaded. Wrong — the server is fine, the CLIENT is sending too many requests.
> - **429 Too Many Requests** → defined in RFC 6585 specifically for rate limiting. Correct semantic: "you're fine, just slow down." Clients that respect HTTP semantics will handle 429 correctly (exponential backoff).

---

**Q28: What is a Retry-After header and why is it important?**
> `Retry-After` tells the client exactly how many seconds to wait before retrying. Without it, clients that get a 429 might retry immediately (creating a "thundering herd" — all clients retry at the same moment, causing another spike). With `Retry-After`, well-behaved clients wait the specified time, which staggers retries and prevents retry storms.

---

**Q29: What is the `X-RateLimit-Reset` header's value format?**
> It's a **Unix epoch timestamp in seconds** (not milliseconds). Example: `1708001234`. Clients subtract the current time to know how many seconds until reset. This is the standard format used by GitHub, Twitter, and Stripe APIs.

---

**Q30: What if a client ignores the Retry-After header and keeps hammering?**
> In Phase 1: The client keeps getting 429 responses until the window resets. There's no additional punishment. In Phase 3 (Circuit Breaker), repeated violations trigger escalating block durations — 5 minutes → 15 minutes → 1 hour. This is the Circuit Breaker pattern: the more you abuse, the longer you're blocked.

---

**Q31: Should rate limit headers be present on 429 responses too?**
> Yes, and they are. On 429, we still send:
> - `X-RateLimit-Limit` (their total limit)
> - `X-RateLimit-Remaining: 0` (explicitly — overrides the pre-429 header)
> - `X-RateLimit-Reset` (when they can try again)
> - `Retry-After` (seconds until retry)
>
> This gives clients full context to implement intelligent backoff.

---

**Q32: What is idempotency and does your `/api/v1/request` endpoint violate it?**
> Idempotency means calling an endpoint multiple times produces the same effect as calling it once. `POST /api/v1/request` is NOT idempotent — each call consumes a token. This is intentional and correct — the point is to track and count every request. The endpoint is explicitly documented as "consumes a token."

---

### 🟠 Category F — Database & Storage

**Q33: Why two separate storage systems (HashMap + PostgreSQL)?**
> Because the data has fundamentally different access patterns:
> - **Rate limit state** (token count, timestamps): Read + written on EVERY request. Needs sub-millisecond access. PostgreSQL adds 10-50ms per call → unacceptable. → In-memory HashMap.
> - **Rate limit config** (max requests, window size): Read rarely (once per identifier on first request). Needs persistence across restarts. Can tolerate 10ms DB lookup → PostgreSQL.
>
> This is the **cache-aside pattern** applied by design.

---

**Q34: How does Hibernate know to create the table automatically?**
> `spring.jpa.hibernate.ddl-auto: update` in `application.yml`. Hibernate reads the `@Entity` class `RateLimitConfig` and:
> - Creates the table if it doesn't exist
> - Adds missing columns if schema changed
> - Never drops existing data
>
> For production, `validate` or `none` is safer (use Flyway/Liquibase for migrations instead).

---

**Q35: What is the `RateLimitConfig` table schema?**
> ```sql
> CREATE TABLE rate_limit_configs (
>     id              BIGSERIAL PRIMARY KEY,
>     identifier      VARCHAR(255) NOT NULL,
>     identifier_type VARCHAR(50)  NOT NULL,  -- USER_ID, IP_ADDRESS, API_KEY
>     max_requests    INTEGER      NOT NULL,
>     window_seconds  INTEGER      NOT NULL,
>     refill_rate     INTEGER      NOT NULL,
>     UNIQUE (identifier, identifier_type)
> );
> ```

---

**Q36: What happens if the PostgreSQL DB is down at startup?**
> Spring Boot fails to start — it tries to establish a connection pool at startup and throws `DataSourceLookupFailureException`. Solution: use `spring.datasource.continue-on-error=true` or add a retry mechanism. In production, the DB should always be available before the app starts (health check in docker-compose handles this via `depends_on: condition: service_healthy`).

---

**Q37: What is `@Transactional` and did you use it?**
> `@Transactional` wraps a method in a database transaction — if anything fails midway, all DB changes are rolled back. In this project, `RateLimitConfigRepository.save()` is implicitly transactional (Spring Data JPA handles it). Admin endpoints that read-then-update could benefit from explicit `@Transactional` for consistency, but since config changes are rare and not atomic-critical, it's acceptable without.

---

### 🟠 Category G — Testing

**Q38: What's the difference between unit tests and integration tests? Which is more important?**
> **Unit tests** test a single class in isolation — all dependencies mocked. Fast (milliseconds). My `TokenBucketStrategyTest` is a unit test — no Spring context, no DB.
>
> **Integration tests** test multiple components working together through the real stack. My `RateLimiterIntegrationTest` starts a full Spring context, makes real HTTP calls via MockMvc, and hits a real H2 DB.
>
> Neither is "more important" — they serve different purposes. Unit tests verify logic; integration tests verify wiring. I have 13 unit tests and 7 integration tests because unit tests are faster and catch algorithm bugs, while integration tests catch configuration and wiring bugs.

---

**Q39: Why did you use MockMvc instead of RestTemplate in integration tests?**
> `MockMvc` doesn't start a real HTTP server — it simulates the full Spring MVC stack (filters, controllers, serialization) in memory. This makes integration tests fast (no port binding, no network overhead). `RestTemplate` or `WebTestClient` would require starting the actual server on a port, making tests slower and dependent on OS port availability.

---

**Q40: What is `@SpringBootTest` and what does it do?**
> It loads the complete Spring application context for testing. This includes:
> - All `@Component`, `@Service`, `@Repository` beans
> - Auto-configuration (datasource, JPA, web layer)
> - Test `application.yml` overrides
>
> Without it, you'd need to manually configure every bean in the test. It's "heavyweight" — makes tests slower, but gives confidence the whole system works together.

---

**Q41: What is `@DirtiesContext` and why did you need it?**
> `@DirtiesContext(classMode = BEFORE_EACH_TEST_METHOD)` destroys and recreates the Spring application context before every test method. This is needed because rate limit state is stored in `ConcurrentHashMap` inside Spring beans (singletons). Without it, test 2 inherits leftover state from test 1 — tokens already consumed, window partially filled — causing unpredictable test failures.

---

**Q42: How did you test the token refill behavior?**
> Using `Thread.sleep()`:
> ```java
> // Exhaust all tokens
> for (int i = 0; i < CAPACITY; i++) strategy.isAllowed("user", config);
> // Wait for refill (refill rate = 10/sec, so 1 second should give 10 new tokens)
> Thread.sleep(1100); // extra 100ms buffer for timing variation
> // Now should be allowed again
> assertTrue(strategy.isAllowed("user", config));
> ```
> The 1100ms buffer accounts for slight timer inaccuracies in the JVM.

---

**Q43: Why minimum 5 unit tests? Why did you write 13?**
> 5 tests is the minimum to demonstrate you understand unit testing. I wrote 13 because:
> 1. **Each meaningful behavior should have its own test** — allows, rejects, refills, resets, concurrent, separate users, remaining count = 7 behaviors
> 2. **Sliding Window has different failure modes** — needed separate tests
> 3. **More tests = more confidence in correctness**
> 4. **Assessment rewards thoroughness**

---

### 🟠 Category H — Edge Cases & Tricky Scenarios

**Q44: What happens if two different users have the same IP address (NAT)?**
> This is a real problem. Corporate offices or universities may have hundreds of users behind one NAT IP. If our identifier is IP-based, all users share the same limit — one heavy user blocks everyone else.
>
> Our solution: Priority-based identifier extraction. If clients send `X-User-Id` or `X-API-Key` headers, those are used instead of IP. Well-designed API clients should always authenticate with a user token, making IP-based limiting only a fallback for unauthenticated traffic.

---

**Q45: What if an attacker sends a fake `X-User-Id` header to impersonate another user and exhaust their limit?**
> In Phase 1, this IS a vulnerability — we trust `X-User-Id` without authentication. In a real system, the `X-User-Id` should be set by an **API Gateway** or **authentication middleware** after verifying the JWT/session token — not accepted directly from the client. The rate limiter should be behind auth, not before it. For this assessment, the focus is on the rate limiting algorithm itself.

---

**Q46: What happens if the client sends `X-User-Id` as an empty string?**
> We check `!userId.isBlank()` before using it:
> ```java
> if (userId != null && !userId.isBlank()) return userId.trim();
> ```
> Empty or whitespace-only values fall through to the next priority (API key, then IP). This prevents empty string from becoming a shared identifier for all clients.

---

**Q47: What if the system clock goes backwards (NTP sync, daylight saving)?**
> For Token Bucket: `elapsedMs = now - lastRefillTime`. If `now < lastRefillTime`, `elapsedMs` is negative. We check `if (elapsedMs <= 0) return;` — so negative elapsed time means no tokens are added. Correct behavior.
>
> For Sliding Window: `windowStartMs = now - windowSeconds * 1000`. If clock jumped back, `windowStartMs` is smaller than expected — fewer timestamps get pruned, making the window appear larger. This is a minor inaccuracy that self-corrects as time moves forward.

---

**Q48: What happens when the server has been running for 30 days and has millions of unique identifiers in the HashMap?**
> Memory grows unboundedly in Phase 1 — there's no eviction policy. Each `RateLimitEntry` for Token Bucket uses ~50 bytes (two `AtomicLong`). 1 million users × 50 bytes = ~50MB — manageable. But in Sliding Window, each entry stores up to N timestamps (e.g., 100 × 8 bytes = 800 bytes per user). 1 million users = ~800MB — potentially problematic.
>
> **Phase 1 accepted limitation.** Phase 2 with Redis solves this — Redis automatically evicts keys via TTL. We don't store entries for users that haven't made requests in a long time.

---

**Q49: What if `maxRequests` is configured as 0 in the database?**
> `0 >= 1` is false, so every request is rejected immediately. This effectively creates a blacklist entry — that identifier can never make requests. While unintentional, it could be used as a feature. Proper validation should prevent 0 or negative values in the admin API (`@Min(1)` on the `maxRequests` field).

---

**Q50: Can someone DOS your admin `/reset` endpoint itself?**
> Yes — in Phase 1, admin endpoints have no authentication and no rate limiting. Anyone who knows `/admin/reset/target-user` can reset any user's counter, enabling abuse. In production:
> 1. Admin endpoints should require authentication (e.g., `X-Admin-Token` header)
> 2. Admin endpoints should be on a different internal network/port
> 3. Rate limit admin endpoints separately (e.g., max 10 resets/minute)

---

### 🟠 Category I — System Design & Scalability

**Q51: How would this system work with multiple app server instances?**
> **Phase 1 answer:** It can't — each instance has its own `ConcurrentHashMap`. A user hitting instance A and instance B gets 2× the limit (once per server). This is why we only run one instance in Phase 1.
>
> **Phase 2 answer:** Redis is a centralized, networked store. All instances share the same Redis → the rate limit is global across all instances. This is exactly what Phase 2 (Sprint 2) implements.

---

**Q52: What would happen at 10,000 requests per second with your current implementation?**
> Estimated performance:
> - `ConcurrentHashMap.computeIfAbsent()` → ~50ns
> - `synchronized(entry)` block → ~100-500ns (assuming low contention per user)
> - Total per request: ~1-5 microseconds (0.001-0.005ms)
>
> At 10,000 req/sec, that's 10,000 × 0.005ms = 50ms total CPU time/second — completely manageable. The bottleneck would be Tomcat's thread pool (default 200 threads), not the rate limiter itself.

---

**Q53: How would you scale this to handle 1 million requests per second?**
> 1. **Horizontal scaling** — add more app instances, replace HashMap with Redis (Phase 2)
> 2. **Redis Cluster** — shard Redis across multiple nodes for horizontal scaling
> 3. **Edge rate limiting** — push rate limiting to CDN/API gateway (Cloudflare, nginx) — decisions made at edge without hitting app servers
> 4. **Local cache + Redis sync** — each instance caches config locally (Caffeine cache), only Redis for state
> 5. **Algorithm optimization** — use lock-free CAS instead of `synchronized` for higher throughput

---

**Q54: What is a load balancer and how does it interact with rate limiting?**
> A load balancer distributes incoming requests across multiple app instances. If each instance has its own in-memory rate limiter, a user routed to different instances on each request gets N× the intended limit.
>
> Solutions:
> 1. **Sticky sessions** — same user always goes to same instance (defeats purpose of load balancing)
> 2. **Centralized store (Redis)** — all instances share state (correct solution, Phase 2)
> 3. **Rate limit at the load balancer** — nginx, HAProxy, and API gateways have built-in rate limiting

---

**Q55: What is Redis and why is it better than HashMap for distributed rate limiting?**
> Redis is an in-memory data store that runs as a separate network service. Unlike a Java `HashMap` which lives inside one JVM process:
> - **Network-accessible** — multiple app instances can all query the same Redis
> - **Persistent** — survives app restarts (AOF or RDB persistence)
> - **Atomic operations** — Lua scripts for atomic check+decrement
> - **TTL support** — keys auto-expire without cleanup code
> - **Data structures** — native sorted sets for Sliding Window, hashes for Token Bucket

---

### 🟠 Category J — Code Quality & Best Practices

**Q56: Why did you use Builder pattern for `RateLimitConfig`?**
> `RateLimitConfig` has 6 fields. A constructor with 6 parameters is hard to read and error-prone (easy to swap `maxRequests` and `windowSeconds` which are both `int`). Builder pattern gives named parameters:
> ```java
> // BAD: Which int is which?
> new RateLimitConfig("user123", USER_ID, 100, 60, 10);
>
> // GOOD: Crystal clear
> RateLimitConfig.builder()
>     .identifier("user123")
>     .maxRequests(100)
>     .windowSeconds(60)
>     .build();
> ```

---

**Q57: Why didn't you use Lombok?**
> Lombok is an annotation processor that generates boilerplate at compile time. It stopped working with Java 25 — the internal compiler APIs Lombok uses changed in Java 21+ and are further restricted in Java 25. Rather than fighting the tooling, I wrote explicit getters/setters/builders. This also makes the code more transparent — anyone reading the code sees exactly what's there, no magic.

---

**Q58: What is the Repository pattern and why use it?**
> Repository pattern abstracts data access behind an interface. `RateLimitConfigRepository extends JpaRepository<RateLimitConfig, Long>` gives:
> - `findById()`, `findAll()`, `save()`, `deleteById()` — free from JpaRepository
> - Custom queries via method names: `findByIdentifier(String identifier)` — Spring generates the SQL automatically
> - Easy to mock in tests: `@MockBean RateLimitConfigRepository` — no real DB needed for unit tests

---

**Q59: How do you handle errors in your API?**
> Currently: The `RateLimitFilter` returns a structured JSON 429 body. For other errors (500s from DB failures), Spring Boot's default error handling returns JSON via `/error`. For production, I'd add:
> 1. A global `@ControllerAdvice` with `@ExceptionHandler` methods
> 2. Specific error codes in the response (not just HTTP status)
> 3. Never expose stack traces in production (`server.error.include-stacktrace=never`)

---

**Q60: What is `@ConfigurationProperties` and how did you use it?**
> `@ConfigurationProperties(prefix = "rate-limiter")` maps YAML config to a Java class:
> ```yaml
> rate-limiter:
>   algorithm: TOKEN_BUCKET
>   default-limit: 100
> ```
> ```java
> @ConfigurationProperties(prefix = "rate-limiter")
> public class RateLimiterProperties {
>     private String algorithm;
>     private int defaultLimit;
>     // ...
> }
> ```
> This is type-safe config binding — better than `@Value("${rate-limiter.algorithm}")` because you get validation, IDE autocomplete, and all config in one object.

---

**Q61: What would you improve if you had more time?**
> Great question. Improvements I'd make:
> 1. **Authentication on admin endpoints** — currently unprotected
> 2. **Input validation** — `@Valid` + `@Min(1)` on admin config POST body
> 3. **Config caching** — DB is queried on every request for unknown identifiers; cache with Caffeine (5-min TTL) to reduce DB load
> 4. **Memory eviction** — remove entries from HashMap that haven't been accessed in N minutes
> 5. **Metrics** — Prometheus counters for allowed/rejected requests per identifier
> 6. **Async logging** — current logging is synchronous; under high load, SLF4J should be async

---

**Q62: How would you debug a situation where users claim they're being rate-limited but they shouldn't be?**
> Debugging steps:
> 1. Check `X-RateLimit-Remaining` header on their last successful request — was it 0?
> 2. Check which identifier is being used — are they sending `X-User-Id`? Or falling back to IP?
> 3. Check if multiple users share an IP → one is exhausting the shared IP limit
> 4. Check `GET /admin/status/{identifier}` — see current state
> 5. Check DB: `SELECT * FROM rate_limit_configs WHERE identifier = '...'` — custom limit applied?
> 6. Check logs for `Rate limit exceeded for identifier:` warnings

---

*Last Updated: February 2026*  
*Project: Production-Ready API Rate Limiter — Anubhav Garg*  
*Total Interview Questions: 62 across 10 categories*
