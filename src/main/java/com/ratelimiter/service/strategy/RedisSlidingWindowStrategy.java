package com.ratelimiter.service.strategy;

import com.ratelimiter.model.RateLimitConfig;
import com.ratelimiter.model.RateLimitEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis-backed Sliding Window Rate Limiting Strategy — Phase 2.
 *
 * Uses a Redis Sorted Set where:
 * member = "{timestamp}-{random}" (unique per request)
 * score = timestamp (epoch ms)
 *
 * The atomic Lua script (scripts/sliding_window.lua) performs:
 * ZREMRANGEBYSCORE → ZCARD → ZADD → EXPIRE
 * in one Redis call, preventing race conditions.
 *
 * Redis Key: rate_limit:window:{identifier}
 * Redis Type: Sorted Set
 * TTL: windowSeconds (auto-expires idle keys)
 *
 * Fallback: On RedisConnectionFailureException, delegates to the
 * in-memory SlidingWindowStrategy transparently.
 */
@Component("redisSlidingWindowStrategy")
@ConditionalOnProperty(name = "rate-limiter.use-redis", havingValue = "true")
public class RedisSlidingWindowStrategy implements RateLimiterStrategy {

    private static final Logger log = LoggerFactory.getLogger(RedisSlidingWindowStrategy.class);
    private static final String KEY_PREFIX = "rate_limit:window:";

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<List<Long>> slidingWindowScript;
    private final SlidingWindowStrategy fallback; // in-memory fallback

    public RedisSlidingWindowStrategy(
            RedisTemplate<String, String> redisTemplate,
            @Qualifier("slidingWindowScript") DefaultRedisScript<List<Long>> slidingWindowScript,
            @Qualifier("slidingWindowStrategy") SlidingWindowStrategy fallback) {
        this.redisTemplate = redisTemplate;
        this.slidingWindowScript = slidingWindowScript;
        this.fallback = fallback;
    }

    @Override
    public boolean isAllowed(String identifier, RateLimitConfig config) {
        try {
            String key = KEY_PREFIX + identifier;
            long now = System.currentTimeMillis();
            long windowMs = config.getWindowSeconds() * 1000L;

            List<Long> result = redisTemplate.execute(
                    slidingWindowScript,
                    List.of(key),
                    String.valueOf(now),
                    String.valueOf(windowMs),
                    String.valueOf(config.getMaxRequests()),
                    String.valueOf(config.getWindowSeconds()));

            if (result == null || result.isEmpty()) {
                log.warn("[Redis] Null result from Lua sliding window for {}. Using fallback.", identifier);
                return fallback.isAllowed(identifier, config);
            }

            boolean allowed = result.get(0) == 1L;
            log.debug("[Redis] Sliding Window: identifier={} allowed={} remaining={}", identifier, allowed,
                    result.get(1));
            return allowed;

        } catch (RedisConnectionFailureException ex) {
            log.warn("[Redis] Connection failed — falling back to in-memory for identifier: {}", identifier);
            return fallback.isAllowed(identifier, config);
        }
    }

    @Override
    public RateLimitEntry getEntry(String identifier) {
        return null; // Not applicable for Redis-backed strategy
    }

    @Override
    public void reset(String identifier, RateLimitConfig config) {
        try {
            String key = KEY_PREFIX + identifier;
            redisTemplate.delete(key);
            log.info("[Redis] Sliding Window reset for identifier: {}", identifier);
        } catch (RedisConnectionFailureException ex) {
            log.warn("[Redis] Connection failed during reset — using fallback for: {}", identifier);
            fallback.reset(identifier, config);
        }
    }

    @Override
    public long getRemainingRequests(String identifier, RateLimitConfig config) {
        try {
            String key = KEY_PREFIX + identifier;
            long now = System.currentTimeMillis();
            long windowStart = now - config.getWindowSeconds() * 1000L;
            Long count = redisTemplate.opsForZSet().count(key, windowStart, now);
            if (count == null)
                return config.getMaxRequests();
            return Math.max(0, config.getMaxRequests() - count);
        } catch (RedisConnectionFailureException ex) {
            log.warn("[Redis] Connection failed — using fallback getRemainingRequests for: {}", identifier);
            return fallback.getRemainingRequests(identifier, config);
        }
    }

    @Override
    public long getResetTimeEpochSeconds(String identifier, RateLimitConfig config) {
        try {
            String key = KEY_PREFIX + identifier;
            // Oldest score in the set = when the earliest request in window expires
            java.util.Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> oldest = redisTemplate
                    .opsForZSet().rangeWithScores(key, 0, 0);
            if (oldest == null || oldest.isEmpty()) {
                return System.currentTimeMillis() / 1000 + config.getWindowSeconds();
            }
            double oldestScore = oldest.iterator().next().getScore();
            return (long) (oldestScore / 1000) + config.getWindowSeconds();
        } catch (RedisConnectionFailureException ex) {
            return System.currentTimeMillis() / 1000 + config.getWindowSeconds();
        }
    }

    @Override
    public String getAlgorithmName() {
        return "REDIS_SLIDING_WINDOW";
    }
}
