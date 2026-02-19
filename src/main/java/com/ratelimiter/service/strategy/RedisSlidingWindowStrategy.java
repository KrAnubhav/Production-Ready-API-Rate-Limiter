package com.ratelimiter.service.strategy;

import com.ratelimiter.circuitbreaker.CircuitBreaker;
import com.ratelimiter.model.RateLimitConfig;
import com.ratelimiter.model.RateLimitEntry;
import com.ratelimiter.metrics.RateLimiterMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Redis-backed Sliding Window Rate Limiting Strategy — Phase 2 + Phase 3.
 *
 * Phase 3 addition: Circuit Breaker wraps every Redis call.
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
 */
@Component("redisSlidingWindowStrategy")
@ConditionalOnProperty(name = "rate-limiter.use-redis", havingValue = "true")
public class RedisSlidingWindowStrategy implements RateLimiterStrategy {

    private static final Logger log = LoggerFactory.getLogger(RedisSlidingWindowStrategy.class);
    private static final String KEY_PREFIX = "rate_limit:window:";

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<List<Long>> slidingWindowScript;
    private final SlidingWindowStrategy fallback;
    private final CircuitBreaker circuitBreaker;

    @Autowired(required = false)
    private RateLimiterMetrics metrics;

    public RedisSlidingWindowStrategy(
            RedisTemplate<String, String> redisTemplate,
            @Qualifier("slidingWindowScript") DefaultRedisScript<List<Long>> slidingWindowScript,
            @Qualifier("slidingWindowStrategy") SlidingWindowStrategy fallback,
            CircuitBreaker circuitBreaker) {
        this.redisTemplate = redisTemplate;
        this.slidingWindowScript = slidingWindowScript;
        this.fallback = fallback;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public boolean isAllowed(String identifier, RateLimitConfig config) {
        // ── Circuit Breaker check ─────────────────────────────────────────────
        if (!circuitBreaker.allowRequest()) {
            log.warn("[CircuitBreaker] OPEN — {} mode for: {}",
                    circuitBreaker.isFailOpen() ? "fail-open (allowing)" : "fail-closed (fallback)",
                    identifier);
            recordFallback();
            return fallback.isAllowed(identifier, config);
        }

        // ── Redis call ────────────────────────────────────────────────────────
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
                log.warn("[Redis] Null Lua sliding window result for {}. Fallback.", identifier);
                circuitBreaker.recordFailure();
                recordFallback();
                return fallback.isAllowed(identifier, config);
            }

            circuitBreaker.recordSuccess();
            boolean allowed = result.get(0) == 1L;
            log.debug("[Redis] Sliding Window: identifier={} allowed={} remaining={}",
                    identifier, allowed, result.get(1));
            return allowed;

        } catch (RedisConnectionFailureException ex) {
            log.warn("[Redis] Connection failed — recording failure + fallback for: {}", identifier);
            circuitBreaker.recordFailure();
            recordFallback();
            return fallback.isAllowed(identifier, config);
        }
    }

    @Override
    public RateLimitEntry getEntry(String identifier) {
        return null;
    }

    @Override
    public void reset(String identifier, RateLimitConfig config) {
        if (!circuitBreaker.allowRequest()) {
            fallback.reset(identifier, config);
            return;
        }
        try {
            redisTemplate.delete(KEY_PREFIX + identifier);
            circuitBreaker.recordSuccess();
            log.info("[Redis] Sliding Window reset for: {}", identifier);
        } catch (RedisConnectionFailureException ex) {
            log.warn("[Redis] Reset connection failed — fallback for: {}", identifier);
            circuitBreaker.recordFailure();
            fallback.reset(identifier, config);
        }
    }

    @Override
    public long getRemainingRequests(String identifier, RateLimitConfig config) {
        if (!circuitBreaker.allowRequest()) {
            return fallback.getRemainingRequests(identifier, config);
        }
        try {
            String key = KEY_PREFIX + identifier;
            long now = System.currentTimeMillis();
            long windowStart = now - config.getWindowSeconds() * 1000L;
            Long count = redisTemplate.opsForZSet().count(key, windowStart, now);
            circuitBreaker.recordSuccess();
            if (count == null)
                return config.getMaxRequests();
            return Math.max(0, config.getMaxRequests() - count);
        } catch (RedisConnectionFailureException ex) {
            circuitBreaker.recordFailure();
            return fallback.getRemainingRequests(identifier, config);
        }
    }

    @Override
    public long getResetTimeEpochSeconds(String identifier, RateLimitConfig config) {
        if (!circuitBreaker.allowRequest()) {
            return System.currentTimeMillis() / 1000 + config.getWindowSeconds();
        }
        try {
            String key = KEY_PREFIX + identifier;
            Set<ZSetOperations.TypedTuple<String>> oldest = redisTemplate.opsForZSet().rangeWithScores(key, 0, 0);
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

    private void recordFallback() {
        if (metrics != null)
            metrics.recordFallback();
    }
}
