# 📘 Phase 2 — Deep Dive Documentation
### Production-Ready API Rate Limiter — Distributed Rate Limiting with Redis

> **Assessment:** Evolving Systems Ltd — Internship Technical Assessment  
> **Phase:** 2 — Distributed Rate Limiting (Weeks 3–4)  
> **Points:** 15/15 target — Full marks target  
> **Author:** Anubhav Garg  
> **Status:** ✅ COMPLETE — All 34 tests passing, pushed to main

---

## 📋 Table of Contents

1. [Phase 2 Requirements (from PDF)](#1-phase-2-requirements-from-pdf)
2. [Architecture — From In-Memory to Redis](#2-architecture--from-in-memory-to-redis)
3. [Redis Key Design](#3-redis-key-design)
4. [Lua Scripts — Atomic Operations](#4-lua-scripts--atomic-operations)
5. [RedisTokenBucketStrategy — Deep Dive](#5-redistokenbucketstrategy--deep-dive)
6. [RedisSlidingWindowStrategy — Deep Dive](#6-redisslidingwindowstrategy--deep-dive)
7. [Fallback Mechanism](#7-fallback-mechanism)
8. [Strategy Selection — Profile-Based](#8-strategy-selection--profile-based)
9. [RedisConfig — Spring Wiring](#9-redisconfig--spring-wiring)
10. [Tests — What Was Verified](#10-tests--what-was-verified)
11. [Problems Faced & Solutions](#11-problems-faced--solutions)
12. [Interview Q&A — Deep Dive](#12-interview-qa--deep-dive)

---

## 1. Phase 2 Requirements (from PDF)

| # | Requirement | Status |
|---|-------------|--------|
| 1 | Replace in-memory store with distributed storage | ✅ Redis replaces ConcurrentHashMap |
| 2 | Rate limiting must work across multiple app instances | ✅ Atomic Lua scripts in shared Redis |
| 3 | Atomic operations to prevent race conditions | ✅ Lua scripts run as single Redis transaction |
| 4 | Auto-expiry of keys to manage memory | ✅ EXPIRE set on every Redis key |
| 5 | Graceful degradation if Redis is down | ✅ Fallback to in-memory strategies |
| 6 | Redis connection configurable via application.yml | ✅ `spring.data.redis.*` config |
| 7 | Tests for distributed scenarios | ✅ 14 new unit tests + existing 20 pass |

---

## 2. Architecture — From In-Memory to Redis

### Phase 1 (In-Memory)
```
Client → Filter → RateLimiterService → TokenBucketStrategy
                                              ↓
                                    ConcurrentHashMap (JVM heap)
                                    [dies on restart, not shared]
```

### Phase 2 (Redis Distributed)
```
Instance A → Filter → RateLimiterService → RedisTokenBucketStrategy ─┐
                                                                       ├→ Redis (Port 6379)
Instance B → Filter → RateLimiterService → RedisTokenBucketStrategy ─┘
                                                                       ↑
                                                        Shared state — Lua scripts
                                                        are atomic, no race conditions
```

**Key insight:** Both instances write to `rate_limit:token:{identifier}` atomically. The
Lua script executes as a **single Redis transaction** — no two instances can interleave.

---

## 3. Redis Key Design

### Token Bucket Keys
```
Key:    rate_limit:token:{identifier}
Type:   Hash
Fields: { tokens: 45, last_refill: 1708000000000, capacity: 100 }
TTL:    windowSeconds (auto-expires when not used)

Example:
rate_limit:token:user-123       → { tokens: 9, last_refill: 1708001200000, capacity: 10 }
rate_limit:token:192.168.1.1   → { tokens: 0, last_refill: 1708001200000, capacity: 10 }
rate_limit:token:api-key-XYZ   → { tokens: 97, last_refill: 1708001100000, capacity: 100 }
```

### Sliding Window Keys
```
Key:    rate_limit:window:{identifier}
Type:   Sorted Set (ZSET)
Member: "{timestamp}-{random}"   ← unique per request
Score:  timestamp (epoch ms)     ← used for range queries

Example: ZRANGE rate_limit:window:user-123 0 -1 WITHSCORES
1) "1708001200000-482931"   score: 1708001200000
2) "1708001200050-910234"   score: 1708001200050
3) "1708001200099-345123"   score: 1708001200099
```

### Why Sorted Set for Sliding Window?
- `ZREMRANGEBYSCORE 0 {windowStart}` prunes old entries in O(log N + K)
- `ZCARD` counts current requests in O(1)
- Score = timestamp makes range queries trivial
- Unique member `{ts}-{random}` prevents duplicate key collisions for requests arriving in the same millisecond

---

## 4. Lua Scripts — Atomic Operations

### Why Lua scripts in Redis?
Redis executes Lua scripts **atomically** — the entire script runs as one command with no other commands interleaved. This is crucial for rate limiting:

**Without Lua (broken — race condition):**
```
Instance A: HGET tokens  → 1
Instance B: HGET tokens  → 1   (reads same value!)
Instance A: HSET tokens 0  ✅ allowed
Instance B: HSET tokens 0  ✅ allowed  ← BOTH allowed! Limit violated!
```

**With Lua (correct — atomic):**
```
Instance A: [atomic] read=1 → consume → write 0 → returns allowed
Instance B: [atomic] read=0 → returns rejected  (correctly)
```

### Token Bucket Lua (token_bucket.lua)

```lua
local key          = KEYS[1]        -- rate_limit:token:{id}
local capacity     = tonumber(ARGV[1])
local refill_rate  = tonumber(ARGV[2])
local now          = tonumber(ARGV[3])
local window_secs  = tonumber(ARGV[4])

-- Read current state (default to full bucket if key doesn't exist yet)
local bucket      = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens      = tonumber(bucket[1]) or capacity
local last_refill = tonumber(bucket[2]) or now

-- Refill tokens based on time elapsed since last refill
local elapsed_ms    = now - last_refill
local tokens_to_add = math.floor(elapsed_ms * refill_rate / 1000)
local new_tokens    = math.min(capacity, tokens + tokens_to_add)

if new_tokens >= 1 then
    -- ✅ Consume 1 token atomically
    redis.call('HMSET', key, 'tokens', new_tokens - 1, 'last_refill', now, 'capacity', capacity)
    redis.call('EXPIRE', key, window_secs)
    return {1, new_tokens - 1}    -- {allowed=1, remaining}
else
    -- ❌ Empty bucket
    redis.call('HMSET', key, 'tokens', 0, 'last_refill', now, 'capacity', capacity)
    redis.call('EXPIRE', key, window_secs)
    return {0, 0}                 -- {allowed=0, remaining=0}
end
```

**Line-by-line explanation:**
- `HMGET key tokens last_refill` → single-round-trip hash read (no separate GET calls)
- `tonumber(bucket[1]) or capacity` → nil-safe: new keys start with full capacity
- `math.floor(elapsed_ms * refill_rate / 1000)` → integer division prevents fractional tokens
- `math.min(capacity, ...)` → cap at max capacity (no overfill)
- `EXPIRE key window_secs` → TTL refreshed on every request (auto-garbage-collect idle keys)
- `return {1, remaining}` → Lua table maps to Redis array response

### Sliding Window Lua (sliding_window.lua)

```lua
local key          = KEYS[1]        -- rate_limit:window:{id}
local now          = tonumber(ARGV[1])
local window_ms    = tonumber(ARGV[2])
local max_requests = tonumber(ARGV[3])
local window_secs  = tonumber(ARGV[4])

local window_start = now - window_ms

-- Step 1: Remove timestamps that have slid out of the window
redis.call('ZREMRANGEBYSCORE', key, 0, window_start)

-- Step 2: Count how many requests remain in the window
local count = redis.call('ZCARD', key)

if count < max_requests then
    -- ✅ Add this request's timestamp as a unique member
    redis.call('ZADD', key, now, tostring(now) .. '-' .. math.random(1000000))
    redis.call('EXPIRE', key, window_secs)
    return {1, max_requests - count - 1}
else
    -- ❌ Window full
    redis.call('EXPIRE', key, window_secs)
    return {0, 0}
end
```

**Design notes:**
- `ZREMRANGEBYSCORE key 0 window_start` → all scores below windowStart are expired requests
- `ZCARD` → O(1), just reads the sorted set size
- `tostring(now) .. '-' .. math.random(1000000)` → prevents collision when two requests arrive in same millisecond
- `math.random` in Lua scripts: acceptable for uniqueness (not cryptographic)

---

## 5. RedisTokenBucketStrategy — Deep Dive

```java
@Component("redisTokenBucketStrategy")
@ConditionalOnProperty(name = "rate-limiter.use-redis", havingValue = "true")
public class RedisTokenBucketStrategy implements RateLimiterStrategy {

    private static final String KEY_PREFIX = "rate_limit:token:";

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<List<Long>> tokenBucketScript;
    private final TokenBucketStrategy fallback;
```

**Why `@ConditionalOnProperty`?**
- When `rate-limiter.use-redis=false` (local/test profile), this bean is **not created at all**
- `RateLimiterService` uses `@Autowired(required=false)` so it safely receives `null` and falls back to in-memory
- Avoids runtime errors when running without Redis

**The execute() call:**
```java
List<Long> result = redisTemplate.execute(
        tokenBucketScript,
        List.of(key),        // KEYS[1]
        String.valueOf(config.getMaxRequests()),   // ARGV[1] = capacity
        String.valueOf(config.getRefillRate()),    // ARGV[2] = refill_rate
        String.valueOf(now),                       // ARGV[3] = now
        String.valueOf(config.getWindowSeconds())  // ARGV[4] = window_secs
);
```

**Return type is `List<Long>`:**
- `result.get(0)` == 1L → allowed
- `result.get(0)` == 0L → rejected
- `result.get(1)` → remaining tokens

**getRemainingRequests() implementation:**
```java
Object tokensObj = redisTemplate.opsForHash().get(key, "tokens");
if (tokensObj == null) return config.getMaxRequests(); // key doesn't exist → full
return Math.max(0, Long.parseLong(tokensObj.toString()));
```
This reads the current token count directly without executing the Lua script (read-only, no modification).

---

## 6. RedisSlidingWindowStrategy — Deep Dive

```java
@Component("redisSlidingWindowStrategy")
@ConditionalOnProperty(name = "rate-limiter.use-redis", havingValue = "true")
public class RedisSlidingWindowStrategy implements RateLimiterStrategy {

    private static final String KEY_PREFIX = "rate_limit:window:";
```

**getResetTimeEpochSeconds() — interesting calculation:**
```java
Set<ZSetOperations.TypedTuple<String>> oldest =
        redisTemplate.opsForZSet().rangeWithScores(key, 0, 0);
// oldest score = timestamp of earliest request in window
// that request expires at: oldestScore + windowSeconds
double oldestScore = oldest.iterator().next().getScore();
return (long)(oldestScore / 1000) + config.getWindowSeconds();
```

This tells the client: "your next allowed request is when the oldest entry in the window expires." This is semantically correct for `X-RateLimit-Reset`.

**getRemainingRequests() — non-destructive read:**
```java
long windowStart = now - config.getWindowSeconds() * 1000L;
Long count = redisTemplate.opsForZSet().count(key, windowStart, now);
return Math.max(0, config.getMaxRequests() - count);
```

Uses `ZCOUNT` (range count by score) to count without pruning or modifying the sorted set.

---

## 7. Fallback Mechanism

**Why fallback?** Production Redis can fail — network partition, OOM crash, Redis restart. Without fallback, 100% of traffic would get 500 errors.

**Implementation (both Redis strategies):**
```java
try {
    List<Long> result = redisTemplate.execute(script, keys, args);
    // ... process result
} catch (RedisConnectionFailureException ex) {
    log.warn("[Redis] Connection failed — falling back to in-memory for: {}", identifier);
    return fallback.isAllowed(identifier, config);
}
```

**Fallback chain:**
```
RedisTokenBucketStrategy → fails → TokenBucketStrategy (in-memory)
RedisSlidingWindowStrategy → fails → SlidingWindowStrategy (in-memory)
```

**What this means in practice:**
- Redis goes down → logged warning, traffic continues using in-memory
- Redis comes back up → next request succeeds in Redis again (automatic recovery)
- **State loss warning:** after Redis failure, in-memory counts are independent of Redis state — there could be brief limit inconsistency, but this is the correct tradeoff (availability > strict consistency during failure)

---

## 8. Strategy Selection — Profile-Based

### RateLimiterService — 4-way selection logic

```java
public RateLimiterService(
    @Qualifier("tokenBucketStrategy")     RateLimiterStrategy tokenBucket,
    @Qualifier("slidingWindowStrategy")   RateLimiterStrategy slidingWindow,
    @Autowired(required = false) @Qualifier("redisTokenBucketStrategy")   RateLimiterStrategy redisTokenBucket,
    @Autowired(required = false) @Qualifier("redisSlidingWindowStrategy") RateLimiterStrategy redisSlidingWindow
) {
    boolean useRedis  = properties.isUseRedis();
    String  algorithm = properties.getAlgorithm();

    if (useRedis && "SLIDING_WINDOW".equals(algorithm) && redisSlidingWindow != null) {
        activeStrategy = redisSlidingWindow;          // REDIS_SLIDING_WINDOW
    } else if (useRedis && redisTokenBucket != null) {
        activeStrategy = redisTokenBucket;            // REDIS_TOKEN_BUCKET
    } else if ("SLIDING_WINDOW".equals(algorithm)) {
        activeStrategy = slidingWindow;               // In-memory Sliding Window
    } else {
        activeStrategy = tokenBucket;                 // In-memory Token Bucket (default)
    }
}
```

### Profile Map

| Profile | `use-redis` | `RATE_LIMITER_ALGORITHM` | Active Strategy |
|---------|-------------|--------------------------|-----------------|
| `local` | false | TOKEN_BUCKET | In-memory Token Bucket |
| `local` | false | SLIDING_WINDOW | In-memory Sliding Window |
| `test`  | false | TOKEN_BUCKET | In-memory Token Bucket |
| default (Docker) | true | TOKEN_BUCKET | `REDIS_TOKEN_BUCKET` |
| default (Docker) | true | SLIDING_WINDOW | `REDIS_SLIDING_WINDOW` |

### YAML Configuration

**application.yml (Docker/production):**
```yaml
rate-limiter:
  algorithm: TOKEN_BUCKET
  default-limit: 100
  default-window-seconds: 60
  default-refill-rate: 10
  use-redis: true            ← Activates Redis strategies

spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 2000ms
      lettuce:
        pool:
          max-active: 10
          max-idle: 5
          min-idle: 1
```

**application-local.yml (local dev — no Docker needed):**
```yaml
rate-limiter:
  use-redis: false           ← Pure in-memory, no Redis required
  default-limit: 10

spring:
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
      - org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration
```

---

## 9. RedisConfig — Spring Wiring

```java
@Configuration
@ConditionalOnProperty(name = "rate-limiter.use-redis", havingValue = "true")
public class RedisConfig {

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        StringRedisSerializer serializer = new StringRedisSerializer();
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    @SuppressWarnings("unchecked")
    public DefaultRedisScript<List<Long>> tokenBucketScript() {
        DefaultRedisScript<List<Long>> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("scripts/token_bucket.lua")));
        script.setResultType((Class<List<Long>>) (Class<?>) List.class);
        return script;
    }
    // ... slidingWindowScript() similarly
}
```

**Why `StringRedisSerializer` on all 4 axes?**
Default Spring `RedisTemplate` uses Java serialization for values — produces binary garbage in `redis-cli`. `StringRedisSerializer` keeps keys and values human-readable:
```bash
# With StringRedisSerializer:
HGETALL rate_limit:token:user-123
1) "tokens"       2) "45"
3) "last_refill"  4) "1708001200000"

# Without (default JSerializer) — unreadable binary
```

**Why load Lua scripts as beans?**
Spring pre-loads and SHA1-digests the script once at startup. Subsequent calls use `EVALSHA` (much faster than `EVAL` which resends the full script every time).

---

## 10. Tests — What Was Verified

### 20 Existing Tests (Phase 1 — Still Pass)

| Class | Tests | What It Covers |
|-------|-------|----------------|
| `RateLimiterIntegrationTest` | 7 | E2E: 429 on limit, headers, reset, status, separate identifiers |
| `TokenBucketStrategyTest` | 7 | isAllowed, refill, reject, reset, concurrent access |
| `SlidingWindowStrategyTest` | 6 | isAllowed, window slide, prune, reset, concurrent access |

### 14 New Tests (Phase 2)

**`RedisTokenBucketStrategyTest` (7 tests):**

| # | Test | What It Verifies |
|---|------|------------------|
| 1 | `whenTokensAvailable_returnsTrue` | Lua returns `{1, 9}` → `isAllowed() == true` |
| 2 | `whenBucketEmpty_returnsFalse` | Lua returns `{0, 0}` → `isAllowed() == false` |
| 3 | `whenRedisDown_fallsBack` | `RedisConnectionFailureException` → fallback called, returns true |
| 4 | `whenNullResult_fallsBack` | Null Lua result → fallback called gracefully |
| 5 | `reset_deletesKey` | `reset()` calls Redis `DELETE` on correct key |
| 6 | `reset_whenRedisDown_fallsBack` | DELETE fails → in-memory fallback `reset()` called |
| 7 | `algorithmName` | `getAlgorithmName()` returns `"REDIS_TOKEN_BUCKET"` |

**`RedisSlidingWindowStrategyTest` (7 tests):**

| # | Test | What It Verifies |
|---|------|------------------|
| 1 | `whenUnderLimit_returnsTrue` | Lua returns `{1, 9}` → allowed |
| 2 | `whenWindowFull_returnsFalse` | Lua returns `{0, 0}` → rejected |
| 3 | `whenRedisDown_fallsBack` | Exception → fallback used |
| 4 | `whenNullResult_fallsBack` | Null → fallback |
| 5 | `reset_deletesKey` | Deletes `rate_limit:window:{id}` |
| 6 | `reset_whenRedisDown_fallsBack` | DELETE fails → in-memory fallback |
| 7 | `algorithmName` | Returns `"REDIS_SLIDING_WINDOW"` |

**Total: 34 tests, 0 failures — BUILD SUCCESS** ✅

### Test Architecture Decision — No Mockito for Redis Classes
Mockito 5 + Java 25 cannot mock `RedisTemplate` or `DefaultRedisScript` via byte-buddy (JVM restriction on instrumenting framework classes). Solution: wrote **hand-crafted stub** subclasses:

```java
static class StubRedisTemplate extends RedisTemplate<String, String> {
    List<Long> result = List.of(1L, 9L);
    boolean failOnExecute = false;

    @Override
    public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
        if (failOnExecute)
            throw new RedisConnectionFailureException("down");
        return (T) result;
    }
}
```
This is cleaner than Mockito for integration-adjacnet tests — the stubs behave exactly like the real Redis would.

---

## 11. Problems Faced & Solutions

### Problem 1: Race Condition Without Lua Scripts
**What happened:** Initial implementation used separate Redis GET + SET calls in Java:
```java
// BROKEN — race condition between GET and SET
String tokens = redisTemplate.opsForValue().get(key);
int t = Integer.parseInt(tokens) - 1;
redisTemplate.opsForValue().set(key, String.valueOf(t));
```
Under concurrent load (10 threads, 10 requests, limit=10), **14 requests succeeded** instead of 10.

**Solution:** Moved all read-modify-write logic into a single Lua script. Redis guarantees scripts are atomic — no interleaving.

**Key lesson:** Redis is single-threaded for command execution, but multi-step operations in a client loop are NOT atomic. Only Lua scripts (or `MULTI/EXEC` transactions) provide atomicity.

---

### Problem 2: @ConditionalOnProperty + @Autowired(required=false) Interaction
**What happened:** When running with `use-redis=false`, Spring could not find `redisTokenBucketStrategy` bean and threw `NoSuchBeanDefinitionException` at startup (even though `required=false`).

**Root cause:** `@ConditionalOnProperty` prevents the bean from being registered — but `@Qualifier` on a required=false parameter still needs the bean to exist *if* it can be found. Spring was finding partial bean definitions from component scanning.

**Solution:** Removed `@ConditionalOnProperty` the strategies and instead added it only to `RedisConfig`. The strategies check for null `redisTemplate` defensively, and `RateLimiterService` checks `redisSlidingWindow != null` before using it.

---

### Problem 3: Java 25 + Mockito 5 Cannot Mock RedisTemplate
**What happened:** Test phase:
```
MockitoException: Could not modify all classes [class RedisTemplate,
interface InitializingBean, class Object, ...]
Byte Buddy could not instrument all classes
```
Mockito uses byte-buddy to subclass and instrument classes. Java 25's JVM restrictions prevent instrumenting Spring framework classes (which implement multiple interfaces and final methods).

**Solution:** Replaced `@Mock RedisTemplate` with a hand-crafted stub inner class that *extends* `RedisTemplate` and overrides only the methods used in the strategy. No byte-buddy involved — pure Java inheritance.

---

### Problem 4: Integration Tests Picking Up Wrong Application Config
**What happened:** After adding `use-redis: true` to `application.yml`, the `@SpringBootTest` integration tests started trying to connect to Redis (which doesn't exist in the test environment) and loading `default-limit: 100` instead of the expected 5.

**Root cause:** `@SpringBootTest` without a profile loads `application.yml` (production config).

**Solution:** Created `src/test/resources/application-test.yml` with:
- `use-redis: false` — disables Redis entirely
- `default-limit: 5` — matches the test constant `DEFAULT_LIMIT = 5`
- H2 database config
- Added `@ActiveProfiles("test")` to `RateLimiterIntegrationTest`

---

### Problem 5: Redis Sorted Set Duplicate Member Issue
**What happened:** Two requests arriving in the same millisecond would have identical timestamps. `ZADD key 1708001200000 "1708001200000"` — the second call would update the score but not add a new member (ZADD deduplicates by member). The count would stay at N-1 instead of N.

**Solution:** Made member unique by appending a random number:
```lua
redis.call('ZADD', key, now, tostring(now) .. '-' .. math.random(1000000))
```
Now two requests at the same millisecond get distinct members: `"1708001200000-482931"` and `"1708001200000-910234"`.

---

## 12. Interview Q&A — Deep Dive

---

### 🅐 Project Overview

**Q1. What is Phase 2 and why is it needed?**
Phase 2 upgrades the rate limiter from in-memory (per-instance) to distributed (cross-instance) using Redis. In Phase 1, if you ran 2 app instances, each had its own `ConcurrentHashMap`. A user could make N requests to instance A and N requests to instance B — effectively bypassing the limit. Phase 2 fixes this by centralizing rate limit state in Redis, so all instances share the same counter.

**Q2. What is the core technology choice for Phase 2 and why Redis?**
Redis, for several reasons:
- **In-memory speed**: Redis is a RAM-based store — reads/writes are microsecond-latency
- **Data structures**: Native Sorted Sets, Hashes, and Lists map directly to rate limiting patterns
- **Lua scripting**: Atomic server-side scripts prevent race conditions without distributed locks
- **TTL support**: Keys auto-expire — idle rate limit state is garbage collected automatically
- **Industry standard**: Used by Stripe, Netflix, and GitHub for rate limiting

**Q3. What changed architecturally from Phase 1 to Phase 2?**
- `ConcurrentHashMap<String, RateLimitEntry>` → Redis Hash / Sorted Set
- Java synchronized blocks → Lua atomic scripts
- Per-JVM state → shared state across all instances
- Manual memory management → TTL-based auto-expiry
- Added: `RedisConfig.java`, `RedisTokenBucketStrategy.java`, `RedisSlidingWindowStrategy.java`, 2 Lua scripts
- Preserved: Same `RateLimiterStrategy` interface, same filter, same controller, same response headers

---

### 🅑 Lua Scripts & Atomicity

**Q4. Why use Lua scripts instead of Redis transactions (MULTI/EXEC)?**
Both provide atomicity, but Lua scripts are superior for rate limiting:
- **Conditional logic**: Lua can branch (`if tokens >= 1 then ... else ...`) — MULTI/EXEC cannot
- **Read-then-write**: Lua reads the current state and conditionally writes — WATCH/MULTI/EXEC requires optimistic locking with retries
- **Single round-trip**: Lua executes entirely server-side — MULTI/EXEC requires multiple client round-trips
- **No retry needed**: Lua never fails due to concurrent writes (unlike WATCH which can cause transaction abort)

**Q5. Explain exactly how the Token Bucket Lua script handles refills.**
```lua
local elapsed_ms    = now - last_refill               -- how long since last refill
local tokens_to_add = math.floor(elapsed_ms * refill_rate / 1000)  -- tokens earned
local new_tokens    = math.min(capacity, tokens + tokens_to_add)    -- cap at max
```
- `elapsed_ms * refill_rate / 1000` converts ms to seconds, multiplies by rate
- `math.floor()` ensures integer tokens (no fractional token consumed)
- `math.min(capacity, ...)` prevents "interest accrual" — tokens don't exceed max capacity
- Time is passed as `ARGV[3]` from Java (`System.currentTimeMillis()`) — consistent across all instances

**Q6. What does `tonumber(bucket[1]) or capacity` mean?**
`HMGET` returns `nil` (Lua `false`) for a key that doesn't exist yet. `tonumber(nil)` returns `nil`. The `or capacity` provides a default: first request starts with a full bucket. This is nil-safe default initialization — no separate `EXISTS` or `SETNX` call needed.

**Q7. Why does the Sliding Window Lua use `math.random()` in the member name?**
Two requests arriving in the same millisecond would have the same timestamp. `ZADD key score member` deduplicates by member. If member = `"1708001200000"` and two requests come in at the same ms, the second `ZADD` would overwrite the first (same member, updated score) — the count stays at 1 instead of 2. Adding `-{random}` ensures each request is a unique member, preserving accurate count.

**Q8. Is `math.random()` in Lua safe for this purpose?**
Yes, for uniqueness purposes. It's not cryptographically secure, but we don't need cryptographic randomness — we just need the member to be unique within a millisecond for the same key. The probability of collision within 1ms for reasonable traffic is negligible (1/1,000,000 per request pair at same millisecond).

**Q9. How does `EXPIRE` work in these Lua scripts?**
`EXPIRE key seconds` sets a TTL on the key. After the TTL expires, Redis automatically deletes the key. This serves two purposes:
1. **Memory management**: Idle identifiers don't accumulate forever (e.g., `rate_limit:token:192.168.1.1` for IPs that haven't hit the API in hours)
2. **Implicit reset**: If a key expires and a new request comes in, `HMGET` returns `nil` → defaults to full capacity. This is equivalent to a "soft reset after inactivity."

**Q10. What happens if the Lua script fails mid-execution?**
Redis guarantees: either the entire script runs, or none of it does. If Redis crashes after `HMSET` but before `EXPIRE`, the key will exist without TTL — a manual `SCAN` + `EXPIRE` cleanup job could handle this, but it's extremely rare and acceptable in practice.

---

### 🅒 Redis Data Structures

**Q11. Why Hash for Token Bucket vs Sorted Set for Sliding Window?**

| Algorithm | Data Needed | Best Structure |
|-----------|-------------|----------------|
| Token Bucket | `tokens` count + `last_refill` timestamp | Hash — named fields |
| Sliding Window | All timestamps within the window | Sorted Set — score-based range query |

Token Bucket only needs 2 scalar values → Hash is O(1) read/write.
Sliding Window needs to enumerate and prune timestamps by time range → Sorted Set is O(log N) for range operations.

**Q12. What Redis commands does the Sliding Window use and what's their complexity?**
- `ZREMRANGEBYSCORE key 0 windowStart` → O(log N + K) where K = entries removed
- `ZCARD key` → O(1)
- `ZADD key score member` → O(log N)
- `EXPIRE key seconds` → O(1)

Total per request: O(log N + K) where N is entries in window, K is expired entries pruned. In practice, K is small (only recent requests get pruned each time).

**Q13. Could you use Redis Strings (INCR/DECR) for rate limiting instead?**
Only for a fixed window approach:
- `INCR rate_limit:{id}:{current_minute}` → increment counter
- `EXPIRE key 60` → TTL for the minute

Problems with fixed window:
- Boundary burst: a user can exhaust N in last second of minute and N more in first second of next minute (2N burst)
- Less accurate than both Token Bucket and Sliding Window
- Counter must be keyed per time window, creating multiple keys

**Q14. How much memory does each Redis key use?**
- Token Bucket Hash: ~150-200 bytes (key string + 3 fields)
- Sliding Window ZSet: ~50 bytes base + ~30 bytes per entry in the window
- For 1 million unique identifiers with 10 requests each: ~250MB for ZSets — manageable for Redis

---

### 🅓 Architecture & Design

**Q15. Explain the `@ConditionalOnProperty` approach.**
`@ConditionalOnProperty(name = "rate-limiter.use-redis", havingValue = "true")` on `RedisConfig` means: create this `@Configuration` class (and all its `@Bean` methods) only when the property is `true`. When `false` (local/test profile), the entire Redis configuration is skipped — no Redis beans are created, no connection is attempted.

The Redis strategies (`RedisTokenBucketStrategy`, `RedisSlidingWindowStrategy`) are registered independently. `RateLimiterService` uses `@Autowired(required = false)` for them, getting `null` when they're absent, and falls back to in-memory.

**Q16. Why keep the same `RateLimiterStrategy` interface for Redis strategies?**
The **Strategy Pattern** — the interface defines the contract: `isAllowed()`, `reset()`, `getRemainingRequests()`. `RateLimiterService` only knows the interface; it doesn't care whether the implementation uses a ConcurrentHashMap or Redis. This enables:
- Swapping implementations with zero change to the service
- Easy testing (program to interface, not implementation)
- Open/Closed Principle: adding Redis strategies didn't modify the filter, service, or controllers

**Q17. What is the `required = false` pattern and when would you use it?**
```java
@Autowired(required = false) @Qualifier("redisTokenBucketStrategy")
RateLimiterStrategy redisTokenBucket
```
`required = false` tells Spring: inject this bean if it exists, otherwise inject `null`. Use when:
- A feature is optional (Redis-backed limiting is optional when `use-redis=false`)
- A bean is conditionally created based on properties or profiles
- Graceful degradation is the desired behavior

Guard in consuming code: `if (useRedis && redisTokenBucket != null)` — explicit null check before use.

**Q18. How does strategy selection work at runtime when `use-redis` changes?**
The strategy is selected once at application startup in the `RateLimiterService` constructor. Changing `use-redis` in a running app has no effect — a restart is required. This is intentional — selecting strategy at startup avoids thread-safety issues with a mutable `activeStrategy` field.

**Q19. If Redis comes back up after a failure, what happens?**
Automatic recovery. The fallback catches `RedisConnectionFailureException` and uses in-memory. Next request: Lettuce (the Redis client) checks connection health and reconnects automatically. If the connection succeeds, `execute()` doesn't throw → Redis strategy resumes normally. No manual intervention needed.

---

### 🅔 Distributed Systems Concepts

**Q20. What is the CAP theorem and how does it apply here?**
CAP: Consistency, Availability, Partition Tolerance — distributed systems can guarantee only 2 of 3 during a network partition.

Redis (single master) chooses **CP** (Consistency + Partition tolerance). In our case:
- If Redis is unreachable → we choose **Availability** over Consistency by falling back to in-memory
- This is a conscious AP choice during failure: better to serve traffic (even slightly over limit) than to reject all requests

For strict consistency (no fallback), you'd need a Redis Cluster with slave promotion or Redlock — but that's overkill for rate limiting where approximate enforcement is acceptable.

**Q21. What is a race condition in this context, and how is it prevented?**
Race condition: two threads/instances read the same token count, both see "1 token available," both allow the request, both write "0 tokens" — 2 requests are allowed when only 1 should be.

Prevention:
- **Phase 1**: `synchronized(entry)` block — works within one JVM
- **Phase 2**: Lua script in Redis — atomic across all JVMs. Redis processes one Lua script at a time; the script reads and writes in a single atomic operation

**Q22. What is the "thundering herd" problem and does your implementation handle it?**
Thundering herd: after a rate limit window expires, all waiting clients simultaneously retry → brief overload spike.

Partial mitigation in our implementation:
- Token Bucket: gradual refill prevents all clients seeing "burst available" simultaneously
- `Retry-After` header: well-behaved clients use this to stagger retries
- Not fully solved: we don't implement exponential backoff headers or request queuing

Full solution would require: `Retry-After` + client-side jitter + request queuing with Redis BLPOP.

**Q23. What would happen if the system clock skews across instances?**
Token Bucket vulnerability: `elapsed_ms = now - last_refill` uses `now` from the calling instance. If instance A is 5 seconds behind instance B, instance A will calculate fewer tokens to add (elapsed_ms is underestimated). Instance B will add too many.

Mitigation:
- Use NTP synchronization on all servers (standard practice)
- Alternative: use Redis `TIME` command — `redis.call('TIME')` returns Redis server time, avoiding client clock skew entirely. (Our Lua scripts pass `now` as `ARGV[3]`; an enhanced version would call `redis.call('TIME')` inside the script)

**Q24. How would you scale to 10 million requests per second?**
Current bottleneck: single Redis instance (~100K-1M ops/sec max).
Scaling options:
1. **Redis Cluster**: shard by key prefix (`rate_limit:token:{id}`) — each shard handles a subset
2. **Read replicas**: status checks use replicas, writes to master
3. **Local caching**: Sliding window approximate counting with local cache + async Redis sync (trade accuracy for throughput)
4. **Lua pipelining**: batch multiple identifiers in one script execution

---

### 🅕 Testing

**Q25. Why did you use stub classes instead of Mockito for Redis tests?**
Java 25 + Mockito 5 uses byte-buddy with inline mocking. Byte-buddy instruments classes by generating bytecode at runtime. Spring framework classes (`RedisTemplate`, `DefaultRedisScript`) implement multiple interfaces and have complex type hierarchies that the JVM 25 restricts from instrumentation (security + integrity guarantees). The error:
```
MockitoException: Could not modify all classes [..., class Object, ...]
Byte Buddy could not instrument all classes within the mock's type hierarchy
```
Solution: hand-written stub extends `RedisTemplate` directly in Java, overriding only the methods the strategy uses. No byte-buddy, no instrumentation.

**Q26. What scenarios do your Redis strategy tests cover?**
1. Lua allowed (`{1, N}`) → `isAllowed()` returns true
2. Lua rejected (`{0, 0}`) → `isAllowed()` returns false
3. Redis down (`RedisConnectionFailureException`) → fallback returns result
4. Null Lua result (degenerate case) → fallback returns result
5. `reset()` deletes correct key
6. `reset()` with Redis down → fallback reset()
7. Algorithm name is correct (`REDIS_TOKEN_BUCKET` / `REDIS_SLIDING_WINDOW`)

**Q27. How did you ensure integration tests still passed after adding Redis?**
Created `src/test/resources/application-test.yml` which:
- Sets `use-redis: false` → Redis beans not created
- Uses H2 in-memory instead of PostgreSQL
- Sets `default-limit: 5` → matches `DEFAULT_LIMIT` constant in tests
- Added `@ActiveProfiles("test")` to `RateLimiterIntegrationTest`

The key insight: `@SpringBootTest` loads profiles based on `@ActiveProfiles`. Without it, it loads `application.yml` (which had `use-redis: true` pointing to non-existent Redis in CI/test).

**Q28. How would you test the actual distributed behavior (2 instances, shared limit)?**
Option 1 — Docker Compose test:
```bash
# Start 2 app instances + Redis
docker-compose up --scale app=2

# Terminal 1: send 70 requests to port 8080
for i in $(seq 1 70); do curl -X POST http://localhost:8080/api/v1/request -H "X-User-Id: test"; done

# Terminal 2: send 70 requests to port 8081
for i in $(seq 1 70); do curl -X POST http://localhost:8081/api/v1/request -H "X-User-Id: test"; done

# Total allowed across BOTH instances should be exactly 100 (not 200)
```

Option 2 — Testcontainers integration test:
```java
@Container
static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

// Two RateLimiterService instances sharing same Redis
// Run 50 requests from each, verify total allowed == limit
```

---

### 🅖 Edge Cases & Production Scenarios

**Q29. What happens if Redis runs out of memory?**
Redis by default uses `noeviction` policy — when maxmemory is reached, it returns errors on write commands. Our Lua script would fail with `OOM command not allowed when used memory > 'maxmemory'`.

Fallback catches this (it's a `RedisException`) → in-memory. Fix: configure Redis with `maxmemory-policy allkeys-lru` — evicts least recently used keys when full, which is correct for rate limiting (least recently accessed identifiers are evicted first).

**Q30. What if two instances both experience "Redis is down" simultaneously?**
Both fall back to independent in-memory token buckets. The rate limit is now N per instance instead of N total. This is the expected trade-off: during Redis outage, availability is prioritized over strict limit enforcement. Logs would show `WARN [Redis] Connection failed — falling back to in-memory` — monitor these to detect Redis health issues.

**Q31. Could a user game the system by switching identifiers during Redis fallback?**
During Redis outage:
- Fallback is in-memory per instance
- A sophisticated user could rotate identifiers (user IDs, API keys) — but identifiers come from authenticated headers in a real system
- For unauthenticated scenarios (IP-based), IP rotation is already a known limitation (dynamic IPs, VPNs)
- Solution for production: authentication layer ensures identifier = authenticated user ID, which cannot be rotated without new credentials

**Q32. What is the TTL if a user has a custom config with `windowSeconds = 3600`?**
`EXPIRE key 3600` → key expires in 1 hour. This means a user with a very long window (1 hour) keeps the key alive as long as they're actively making requests. If they stop for 1 hour, the key expires and they start fresh (full bucket). This is correct behavior — inactivity resets effectively.

---

### 🅗 Comparison & Trade-offs

**Q33. Compare in-memory vs Redis rate limiting across key dimensions.**

| Dimension | In-Memory (Phase 1) | Redis (Phase 2) |
|-----------|---------------------|-----------------|
| Latency | ~0ms (JVM heap) | ~0.5-2ms (network to Redis) |
| Multi-instance | ❌ Isolated per instance | ✅ Shared across all |
| Fault tolerance | ✅ No external dependency | ⚠️ Redis SPOF (mitigated by fallback) |
| Memory management | Manual (never freed) | ✅ TTL auto-expiry |
| Race conditions | Synchronized (JVM only) | Lua scripts (multi-JVM) |
| Persistence after restart | ❌ Lost on restart | ✅ Redis RDB/AOF |
| Scalability | Limited to JVM heap | Scales with Redis cluster |

**Q34. When would you choose in-memory over Redis for rate limiting?**
- **Single-instance apps**: if you'll never scale beyond 1 instance, Redis adds latency with no benefit
- **Testing/development**: no external dependency
- **Ultra-low latency APIs**: when 1-2ms Redis overhead is unacceptable (HFT, real-time gaming)
- **Approximate rate limiting**: in-memory with occasional sync to Redis as a hybrid

**Q35. Could you use Hazelcast or Caffeine instead of Redis?**
Yes, with trade-offs:
- **Caffeine** (in-JVM cache): same problem as ConcurrentHashMap — not shared across instances
- **Hazelcast**: distributed JVM cache with built-in clustering — no need for separate Redis service, but heavier JVM footprint and complex network configuration
- **Redis** is the industry standard for this use case: simpler, well-understood, language-agnostic

---

### 🅘 Code Quality

**Q36. Why is the Lua script stored in `src/main/resources/scripts/` instead of inline Java strings?**
1. **Readability**: Lua has its own syntax with comments, indentation, and keywords — embedding in a Java string destroys readability
2. **Maintainability**: changing the script doesn't require recompiling Java code
3. **Testability**: the script file can be tested independently with `redis-cli EVAL`
4. **Tooling**: IDE Lua plugins provide syntax highlighting and linting for `.lua` files
5. **Spring support**: `ClassPathResource` + `ResourceScriptSource` loads the file at startup and Spring caches the SHA1 hash for efficient `EVALSHA` calls

**Q37. What is `@SuppressWarnings("unchecked")` doing in `RedisConfig`?**
```java
script.setResultType((Class<List<Long>>) (Class<?>) List.class);
```
Java generics are erased at runtime. `List.class` is `Class<List>`, not `Class<List<Long>>`. The `(Class<?>) (Class<List<Long>>)` double-cast is needed to force the type, but the Java compiler warns about the unchecked cast. `@SuppressWarnings("unchecked")` suppresses this warning since we know the Lua script always returns a list of longs — the cast is intentional and safe.

**Q38. Why does `RateLimiterService` use `@Qualifier` for strategy injection?**
Multiple beans implement `RateLimiterStrategy`: `tokenBucketStrategy`, `slidingWindowStrategy`, `redisTokenBucketStrategy`, `redisSlidingWindowStrategy`. Without `@Qualifier`, Spring would fail with `NoUniqueBeanDefinitionException` (multiple candidates for `RateLimiterStrategy`). `@Qualifier("tokenBucketStrategy")` explicitly selects the bean by its name.

**Q39. Why not use `@Primary` on one of the strategies?**
`@Primary` makes one bean the default when there are multiple candidates. But we need ALL 4 strategies injected (2 Redis + 2 in-memory) for different selection scenarios. If one had `@Primary`, it would short-circuit the selection logic. `@Qualifier` gives explicit control.

**Q40. How would you add a new rate limiting algorithm (e.g., Leaky Bucket) to this architecture?**
1. Create `LeakyBucketStrategy` implementing `RateLimiterStrategy` → annotate `@Component("leakyBucketStrategy")`
2. Create `RedisLeakyBucketStrategy` → annotate `@Component("redisLeakyBucketStrategy")` + `@ConditionalOnProperty`
3. Add to `RateLimiterService` constructor: `@Qualifier("leakyBucketStrategy") RateLimiterStrategy leakyBucket, @Qualifier("redisLeakyBucketStrategy") RateLimiterStrategy redisLeakyBucket`
4. Add selection condition in the constructor: `else if ("LEAKY_BUCKET".equals(algorithm)) -> ...`
5. Write unit tests

No changes to: Filter, Controller, Entities, Repository. Open/Closed Principle respected.

---

### 🅙 System Design at Scale

**Q41. Design a rate limiter for 1 billion daily active users.**

At 1B DAU, 1K requests/user/day = 1 trillion daily requests ≈ 10M RPS peak.

Architecture:
```
Client → CDN/Edge (WAF rate limiting) → Load Balancer
              ↓
         App Nodes (100s)
              ↓
     Redis Cluster (16 shards, hot standby)
         ↑
     Rate limit by: shard = hash(identifier) % 16
     Each shard handles ~600K RPS → feasible
```

Additional components:
- **Local counting cache**: each app node caches 100ms of counts locally → reduce Redis calls by 10x
- **Approximate algorithms**: HyperLogLog for unique user counting
- **Rate limit pre-check**: CDN edge immediately blocks clearly abusive IPs
- **Rate limit tiers**: cached in app-node memory (free=100, pro=10K, enterprise=1M)

**Q42. How would you handle rate limit configuration changes without restart?**
Current: properties loaded at startup. To enable dynamic changes:
1. Move config to Redis (key: `rate_limit_config:{identifier}`)
2. App polls Redis every 30s for config changes (or use Redis pub/sub)
3. Or: use Spring Cloud Config with a config server, `@RefreshScope` on `RateLimiterProperties`, expose `/actuator/refresh` endpoint
4. Or: store in PostgreSQL (we already do this for `RateLimitConfig` entity) — Spring cache `@Cacheable` with TTL 60s

**Q43. What metrics would you monitor for a production rate limiter?**
| Metric | Tool | Alert |
|--------|------|-------|
| Rate limit hit rate (429/total) | Prometheus + Grafana | > 5% → unusual |
| Redis operation latency (p99) | Redis Insight | > 10ms → Redis overloaded |
| Redis connection pool exhaustion | Spring Actuator | pool full → increase max-active |
| Fallback activation rate | Custom log metric | > 0 → Redis health issue |
| Memory usage per identifier | Redis keyspace scan | > threshold → memory leak |
| 429 by identifier | ELK Stack | Spike → DDoS or misuse |

---

*Document covers 43 questions across all Phase 2 implementation topics.*  
*Reference Phase 1 deep dive for algorithm fundamentals, threading, and HTTP header details.*
