# Sprint 2 — Redis Distributed Rate Limiting Walkthrough

## ✅ What Was Built

Sprint 2 implements **Redis as a centralized rate limit store**, replacing the in-memory `ConcurrentHashMap`. Multiple app instances now share a single Redis state using atomic Lua scripts.

---

## 📁 New Files Created

| File | Purpose |
|------|---------|
| [src/main/resources/scripts/token_bucket.lua](file:///Users/anubhavgarg/Downloads/API-Rate-Limiter/src/main/resources/scripts/token_bucket.lua) | Atomic Lua: HMGET → refill → HMSET → EXPIRE |
| [src/main/resources/scripts/sliding_window.lua](file:///Users/anubhavgarg/Downloads/API-Rate-Limiter/src/main/resources/scripts/sliding_window.lua) | Atomic Lua: ZREMRANGEBYSCORE → ZCARD → ZADD → EXPIRE |
| `src/main/java/.../config/RedisConfig.java` | `RedisTemplate<String,String>` + script beans (conditional on `use-redis=true`) |
| `src/main/java/.../strategy/RedisTokenBucketStrategy.java` | Redis Token Bucket via Lua, fallback to in-memory |
| `src/main/java/.../strategy/RedisSlidingWindowStrategy.java` | Redis Sliding Window via Lua, fallback to in-memory |
| [src/test/resources/application-test.yml](file:///Users/anubhavgarg/Downloads/API-Rate-Limiter/src/test/resources/application-test.yml) | Test config: H2, limit=5, Redis disabled |
| `src/test/.../RedisTokenBucketStrategyTest.java` | 7 unit tests (no live Redis, using stubs) |
| `src/test/.../RedisSlidingWindowStrategyTest.java` | 7 unit tests (no live Redis, using stubs) |

## 📝 Modified Files

| File | Change |
|------|--------|
| [RateLimiterProperties.java](file:///Users/anubhavgarg/Downloads/API-Rate-Limiter/src/main/java/com/ratelimiter/config/RateLimiterProperties.java) | Added `useRedis` boolean |
| [RateLimiterService.java](file:///Users/anubhavgarg/Downloads/API-Rate-Limiter/src/main/java/com/ratelimiter/service/RateLimiterService.java) | 4-way strategy selection (Redis/in-memory × Token/Sliding) |
| [application.yml](file:///Users/anubhavgarg/Downloads/API-Rate-Limiter/src/test/resources/application.yml) | Added `use-redis: true` |
| [application-local.yml](file:///Users/anubhavgarg/Downloads/API-Rate-Limiter/src/main/resources/application-local.yml) | Added `use-redis: false` |
| [RateLimiterIntegrationTest.java](file:///Users/anubhavgarg/Downloads/API-Rate-Limiter/src/test/java/com/ratelimiter/RateLimiterIntegrationTest.java) | Added `@ActiveProfiles("test")` |

---

## 🔑 Key Design Decisions

### 1. Atomic Lua Scripts
All Redis operations happen in a **single atomic script** — no race conditions between instances.

```lua
-- Token Bucket: read → refill → consume → write → expire (one atomic call)
local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')
-- ... refill calculation ...
if new_tokens >= 1 then
    redis.call('HMSET', key, 'tokens', new_tokens - 1, 'last_refill', now)
    redis.call('EXPIRE', key, window_secs)
    return {1, new_tokens - 1}
end
```

### 2. Graceful Fallback
Both Redis strategies catch `RedisConnectionFailureException` and transparently delegate to the in-memory strategy:

```java
} catch (RedisConnectionFailureException ex) {
    log.warn("[Redis] Connection failed — falling back to in-memory");
    return fallback.isAllowed(identifier, config);
}
```

### 3. Profile-Based Strategy Selection
| Profile | `use-redis` | Active Strategy |
|---------|-------------|-----------------|
| `local` | `false` | In-memory TokenBucket / SlidingWindow |
| [test](file:///Users/anubhavgarg/Downloads/API-Rate-Limiter/src/test/java/com/ratelimiter/service/strategy/SlidingWindowStrategyTest.java#51-68)  | `false` | In-memory (for unit/integration tests) |
| `default` (Docker) | `true` | Redis TokenBucket / RedisSlidingWindow |

### 4. Redis Key Design
```
Token Bucket:   rate_limit:token:{identifier}   → Hash { tokens, last_refill, capacity }
Sliding Window: rate_limit:window:{identifier}  → Sorted Set { score=timestamp }
```
Both keys have TTL = [windowSeconds](file:///Users/anubhavgarg/Downloads/API-Rate-Limiter/src/main/java/com/ratelimiter/model/RateLimitConfig.java#93-97) — unused keys auto-expire.

---

## 🧪 Test Results

```
Tests run: 34, Failures: 0, Errors: 0, Skipped: 0

  RateLimiterIntegrationTest      — 7/7  ✅
  TokenBucketStrategyTest         — 7/7  ✅
  SlidingWindowStrategyTest       — 6/6  ✅
  RedisTokenBucketStrategyTest    — 7/7  ✅  (NEW)
  RedisSlidingWindowStrategyTest  — 7/7  ✅  (NEW)

BUILD SUCCESS
```

---

## 🚀 How to Run with Redis

```bash
# Start everything with Docker (Redis + PostgreSQL + App)
docker-compose up --build

# Verify Redis strategy is active
curl http://localhost:8080/api/v1/status?identifier=test
# Response: { "algorithm": "REDIS_TOKEN_BUCKET", "totalLimit": 100, ... }

# Check the Redis key directly
docker exec rate-limiter-redis redis-cli HGETALL "rate_limit:token:test"

# Check TTL is set
docker exec rate-limiter-redis redis-cli TTL "rate_limit:token:test"

# Test that rate limit is enforced
for i in $(seq 1 110); do \
  curl -s -o /dev/null -w "%{http_code}\n" -X POST \
  http://localhost:8080/api/v1/request -H "X-User-Id: load-test"; \
done
# First 100 → 200, after that → 429
```

## 💻 How to Run Locally (No Docker)

```bash
mvn spring-boot:run -Dspring.profiles.active=local
# Uses in-memory strategies — no Redis needed
```

---

## 📦 Commit

```
feat(phase2): implement Redis distributed rate limiting with Lua scripts, fallback, and 14 new tests
Commit: 4416261
```
