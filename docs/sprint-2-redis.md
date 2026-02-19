# 🔴 Sprint 2 — Distributed Rate Limiting with Redis (Phase 2)

> **Duration:** Week 3 – Week 4  
> **Goal:** Replace in-memory HashMap with Redis for distributed, multi-instance rate limiting  
> **Phase 2 Choice:** Option B — Distributed Rate Limiting with Redis  
> **Points at stake:** 15 pts  
> **Status:** 🔲 TODO

---

## 🎯 Sprint Goal

Upgrade the Phase 1 in-memory rate limiter to use **Redis as a centralized, distributed store**, so multiple API server instances share the same rate limit counters using atomic Lua scripts to prevent race conditions.

---

## 📋 User Stories

| # | Story | Acceptance Criteria | Status |
|---|-------|---------------------|--------|
| US-08 | As an ops engineer, I want rate limits to work across multiple app instances | 3 instances sharing Redis don't exceed 1 combined limit | 🔲 Todo |
| US-09 | As a developer, I want atomic rate limit checks to prevent race conditions | Lua script used for atomic token check + decrement | 🔲 Todo |
| US-10 | As an ops engineer, I want Redis keys to auto-expire so memory is managed | TTL set on every Redis key | 🔲 Todo |
| US-11 | As an ops engineer, I want the app to degrade gracefully if Redis is down | Falls back to in-memory when Redis unreachable | 🔲 Todo |
| US-12 | As a developer, I want to configure Redis connection in `application.yml` | `spring.data.redis.*` config works | 🔲 Todo |

---

## 🏗️ Architecture — Redis Integration

```
Client Request
      │
      ▼
RateLimitFilter (unchanged)
      │
      ▼
RateLimiterService
      │
      ├── strategy = "TOKEN_BUCKET"   → RedisTokenBucketStrategy (new)
      └── strategy = "SLIDING_WINDOW" → RedisSlidingWindowStrategy (new)
                                            │
                                            ▼
                                      Redis (Port 6379)
                                      Key: rate_limit:{identifier}
                                      Value: Hash { tokens, last_refill }
                                      TTL: windowSeconds
```

### Redis Key Design

```
TOKEN BUCKET:
  Key:   rate_limit:token:{identifier}
  Type:  Hash
  Fields: { tokens: 45, capacity: 100, refill_rate: 10, last_refill: 1708000000000 }
  TTL:   3600 seconds

SLIDING WINDOW:
  Key:   rate_limit:window:{identifier}
  Type:  Sorted Set (member=timestamp, score=timestamp)
  TTL:   windowSeconds
```

---

## 📋 Tasks Breakdown

### Week 3 — Redis Core Implementation

#### Task 1: Redis Configuration
- [ ] Enable `spring-boot-starter-data-redis` (already in `pom.xml`)
- [ ] Create `RedisConfig.java` — configure `RedisTemplate<String, String>`
- [ ] Configure connection pool (Lettuce client)
- [ ] Add Redis settings to `application.yml`
- [ ] Remove `autoconfigure.exclude` from `application-local.yml` (now Redis is active)

**`application.yml` Redis config:**
```yaml
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

---

#### Task 2: Redis Token Bucket Strategy

**Create:** `src/main/java/com/ratelimiter/service/strategy/RedisTokenBucketStrategy.java`

Uses an **atomic Lua script** to read + update tokens in a single Redis call (prevents race conditions):

```lua
-- Lua Script: token_bucket.lua
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local window_seconds = tonumber(ARGV[4])

local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens = tonumber(bucket[1]) or capacity
local last_refill = tonumber(bucket[2]) or now

-- Refill tokens based on elapsed time
local elapsed_ms = now - last_refill
local tokens_to_add = math.floor(elapsed_ms * refill_rate / 1000)
local new_tokens = math.min(capacity, tokens + tokens_to_add)

if new_tokens >= 1 then
    redis.call('HMSET', key, 'tokens', new_tokens - 1, 'last_refill', now)
    redis.call('EXPIRE', key, window_seconds)
    return {1, new_tokens - 1}  -- {allowed, remaining}
else
    redis.call('EXPIRE', key, window_seconds)
    return {0, 0}  -- {rejected, remaining}
end
```

**Java class responsibilities:**
- Load Lua script using `RedisScript<List<Long>>`
- Call `redisTemplate.execute(script, keys, args)`
- Implement `RateLimiterStrategy` interface
- Fallback to in-memory on `RedisConnectionFailureException`

---

#### Task 3: Redis Sliding Window Strategy

**Create:** `src/main/java/com/ratelimiter/service/strategy/RedisSlidingWindowStrategy.java`

Uses **Redis Sorted Set** — timestamps as both member and score, then `ZRANGEBYSCORE` to count within window:

```
ZADD rate_limit:window:{id} {timestamp} {timestamp}
ZREMRANGEBYSCORE rate_limit:window:{id} 0 {windowStart}
ZCARD rate_limit:window:{id}    → count in window
EXPIRE rate_limit:window:{id} {windowSeconds}
```

**Atomic Lua script for sliding window:**
```lua
local key = KEYS[1]
local now = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])
local max_requests = tonumber(ARGV[3])
local window_seconds = tonumber(ARGV[4])

local window_start = now - window_ms

-- Remove old entries
redis.call('ZREMRANGEBYSCORE', key, 0, window_start)

-- Count current entries
local count = redis.call('ZCARD', key)

if count < max_requests then
    redis.call('ZADD', key, now, now)
    redis.call('EXPIRE', key, window_seconds)
    return {1, max_requests - count - 1}  -- {allowed, remaining}
else
    redis.call('EXPIRE', key, window_seconds)
    return {0, 0}  -- {rejected, remaining}
end
```

---

#### Task 4: Fallback Mechanism

**Create:** `src/main/java/com/ratelimiter/service/strategy/FallbackAwareStrategy.java`

```
Redis available?
    YES → Use RedisTokenBucketStrategy / RedisSlidingWindowStrategy
    NO  → Log warning, fall back to in-memory TokenBucketStrategy
```

- Wraps any Redis strategy with try/catch for `RedisConnectionFailureException`
- Uses Spring `@Retryable` or manual retry with 3-attempt circuit

---

### Week 4 — Testing & Polish

#### Task 5: Update Unit Tests for Redis Strategies
- [ ] `RedisTokenBucketStrategyTest` — mock `RedisTemplate`, test Lua script execution
- [ ] `RedisSlidingWindowStrategyTest` — mock sorted set operations
- [ ] `FallbackStrategyTest` — simulate Redis down, verify fallback activates

#### Task 6: Integration Tests — Distributed Scenario
- [ ] Use `@SpringBootTest` with embedded Redis (`testcontainers`)
- [ ] Test: 3 concurrent threads → total allowed = exactly N
- [ ] Test: start with in-memory, switch to Redis, verify continuity
- [ ] Test: Redis down → fallback works → Redis recovers → back to Redis

**Add to `pom.xml` for testing:**
```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers</artifactId>
    <version>1.19.3</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>com.redis</groupId>
    <artifactId>testcontainers-redis</artifactId>
    <version>2.0.1</version>
    <scope>test</scope>
</dependency>
```

#### Task 7: Load Test (Multi-Instance)
- [ ] Start 2 app instances (port 8080, 8081) sharing same Redis
- [ ] Fire 200 total requests split across both instances
- [ ] Verify total allowed = exactly 100 (not 200)
- [ ] Document results in this sprint doc

#### Task 8: Documentation Updates
- [ ] Update `README.md` — add Redis setup section
- [ ] Update `architecture.md` — show Redis in the flow
- [ ] Add Redis Lua script explanation to code comments
- [ ] Update Swagger description to mention distributed support

---

## 📁 New Files to Create

```
src/main/java/com/ratelimiter/
├── config/
│   └── RedisConfig.java                          ← NEW
├── service/strategy/
│   ├── RedisTokenBucketStrategy.java             ← NEW
│   ├── RedisSlidingWindowStrategy.java           ← NEW
│   └── FallbackAwareStrategy.java                ← NEW
└── scripts/
    ├── token_bucket.lua                          ← NEW
    └── sliding_window.lua                        ← NEW

src/test/java/com/ratelimiter/
└── service/strategy/
    ├── RedisTokenBucketStrategyTest.java         ← NEW
    ├── RedisSlidingWindowStrategyTest.java       ← NEW
    └── FallbackStrategyTest.java                 ← NEW
```

---

## ✅ Sprint 2 Definition of Done

- [ ] Redis replaces in-memory HashMap for rate limit state
- [ ] Lua scripts used for atomic operations (no race conditions)
- [ ] Fallback to in-memory when Redis is down
- [ ] TTL auto-cleanup working (verify with `redis-cli TTL`)
- [ ] Multi-instance test passes (2 instances, shared limit respected)
- [ ] 3+ new tests for Redis strategies
- [ ] `docker-compose up` includes Redis and it works end-to-end

---

## 🧪 Manual Verification Steps

```bash
# 1. Start with Docker (Redis included)
docker-compose up --build

# 2. Hit same user from two terminal tabs simultaneously
# Terminal 1
for i in $(seq 1 60); do curl -s -o /dev/null -w "%{http_code}\n" \
  -X POST http://localhost:8080/api/v1/request -H "X-User-Id: shared-user"; done &

# Terminal 2
for i in $(seq 1 60); do curl -s -o /dev/null -w "%{http_code}\n" \
  -X POST http://localhost:8080/api/v1/request -H "X-User-Id: shared-user"; done &

# Total 200 allowed = 10 if default limit is 10 per window
# Verify via Redis CLI
redis-cli HGETALL "rate_limit:token:shared-user"

# 3. Test fallback — stop Redis, verify app still works
docker-compose stop redis
curl -X POST http://localhost:8080/api/v1/request -H "X-User-Id: test"
# Should fall back to in-memory, return 200
```

---

## ⬅️ Previous: [Sprint 1 — Core Rate Limiter](./sprint-1-core.md)  
## ➡️ Next: [Sprint 3 — Circuit Breaker & Submission](./sprint-3-circuit-breaker.md)
