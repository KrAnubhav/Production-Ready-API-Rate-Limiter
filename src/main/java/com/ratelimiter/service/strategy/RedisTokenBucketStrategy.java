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
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis-backed Token Bucket Rate Limiting Strategy — Phase 2 + Phase 3.
 *
 * Phase 3 addition: Circuit Breaker wraps every Redis call.
 *
 * Decision flow:
 * 1. circuitBreaker.allowRequest()?
 * NO + fail-open → allow through (fallback)
 * NO + fail-closed → delegate to in-memory fallback
 * YES → proceed to Redis Lua script
 * 2. Redis call succeeds → circuitBreaker.recordSuccess()
 * 3. Redis call fails → circuitBreaker.recordFailure() → may trip circuit OPEN
 *
 * Redis Key: rate_limit:token:{identifier}
 * Redis Type: Hash { tokens, last_refill, capacity }
 * TTL: windowSeconds (auto-expires unused keys)
 */
@Component("redisTokenBucketStrategy")
@ConditionalOnProperty(name = "rate-limiter.use-redis", havingValue = "true")
public class RedisTokenBucketStrategy implements RateLimiterStrategy {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketStrategy.class);
    private static final String KEY_PREFIX = "rate_limit:token:";

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<List<Long>> tokenBucketScript;
    private final TokenBucketStrategy fallback;
    private final CircuitBreaker circuitBreaker;

    // Optional — only wired when metrics bean is available (use-redis=true
    // activates it)
    @Autowired(required = false)
    private RateLimiterMetrics metrics;

    public RedisTokenBucketStrategy(
            RedisTemplate<String, String> redisTemplate,
            @Qualifier("tokenBucketScript") DefaultRedisScript<List<Long>> tokenBucketScript,
            @Qualifier("tokenBucketStrategy") TokenBucketStrategy fallback,
            CircuitBreaker circuitBreaker) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketScript = tokenBucketScript;
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

            List<Long> result = redisTemplate.execute(
                    tokenBucketScript,
                    List.of(key),
                    String.valueOf(config.getMaxRequests()),
                    String.valueOf(config.getRefillRate()),
                    String.valueOf(now),
                    String.valueOf(config.getWindowSeconds()));

            if (result == null || result.isEmpty()) {
                log.warn("[Redis] Null script result for {}. Using fallback.", identifier);
                circuitBreaker.recordFailure();
                recordFallback();
                return fallback.isAllowed(identifier, config);
            }

            circuitBreaker.recordSuccess();
            boolean allowed = result.get(0) == 1L;
            log.debug("[Redis] Token Bucket: identifier={} allowed={} remaining={}",
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
        return null; // Redis state not mapped to RateLimitEntry
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
            log.info("[Redis] Token Bucket reset for: {}", identifier);
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
            Object tokensObj = redisTemplate.opsForHash().get(key, "tokens");
            circuitBreaker.recordSuccess();
            if (tokensObj == null)
                return config.getMaxRequests();
            return Math.max(0, Long.parseLong(tokensObj.toString()));
        } catch (RedisConnectionFailureException ex) {
            circuitBreaker.recordFailure();
            return fallback.getRemainingRequests(identifier, config);
        }
    }

    @Override
    public long getResetTimeEpochSeconds(String identifier, RateLimitConfig config) {
        return System.currentTimeMillis() / 1000 + config.getWindowSeconds();
    }

    @Override
    public String getAlgorithmName() {
        return "REDIS_TOKEN_BUCKET";
    }

    private void recordFallback() {
        if (metrics != null)
            metrics.recordFallback();
    }
}
