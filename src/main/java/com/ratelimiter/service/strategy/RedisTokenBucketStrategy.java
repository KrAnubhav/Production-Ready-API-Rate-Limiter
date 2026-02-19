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
 * Redis-backed Token Bucket Rate Limiting Strategy — Phase 2.
 *
 * Uses an atomic Lua script (scripts/token_bucket.lua) to perform:
 * HMGET → refill calculation → HMSET → EXPIRE
 * in a single Redis round-trip, preventing race conditions across
 * multiple application instances.
 *
 * Redis Key: rate_limit:token:{identifier}
 * Redis Type: Hash { tokens, last_refill, capacity }
 * TTL: windowSeconds (auto-expires unused keys)
 *
 * Fallback: On RedisConnectionFailureException, delegates to the
 * in-memory TokenBucketStrategy transparently.
 */
@Component("redisTokenBucketStrategy")
@ConditionalOnProperty(name = "rate-limiter.use-redis", havingValue = "true")
public class RedisTokenBucketStrategy implements RateLimiterStrategy {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketStrategy.class);
    private static final String KEY_PREFIX = "rate_limit:token:";

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<List<Long>> tokenBucketScript;
    private final TokenBucketStrategy fallback; // in-memory fallback

    public RedisTokenBucketStrategy(
            RedisTemplate<String, String> redisTemplate,
            @Qualifier("tokenBucketScript") DefaultRedisScript<List<Long>> tokenBucketScript,
            @Qualifier("tokenBucketStrategy") TokenBucketStrategy fallback) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketScript = tokenBucketScript;
        this.fallback = fallback;
    }

    @Override
    public boolean isAllowed(String identifier, RateLimitConfig config) {
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
                log.warn("[Redis] Null result from Lua script for {}. Using fallback.", identifier);
                return fallback.isAllowed(identifier, config);
            }

            boolean allowed = result.get(0) == 1L;
            log.debug("[Redis] Token Bucket: identifier={} allowed={} remaining={}", identifier, allowed,
                    result.get(1));
            return allowed;

        } catch (RedisConnectionFailureException ex) {
            log.warn("[Redis] Connection failed — falling back to in-memory for identifier: {}", identifier);
            return fallback.isAllowed(identifier, config);
        }
    }

    @Override
    public RateLimitEntry getEntry(String identifier) {
        // Redis state is not mapped to RateLimitEntry — return null (not used in
        // filter)
        return null;
    }

    @Override
    public void reset(String identifier, RateLimitConfig config) {
        try {
            String key = KEY_PREFIX + identifier;
            // Delete the key — next request will start with a full bucket
            redisTemplate.delete(key);
            log.info("[Redis] Token Bucket reset for identifier: {}", identifier);
        } catch (RedisConnectionFailureException ex) {
            log.warn("[Redis] Connection failed during reset — using fallback for: {}", identifier);
            fallback.reset(identifier, config);
        }
    }

    @Override
    public long getRemainingRequests(String identifier, RateLimitConfig config) {
        try {
            String key = KEY_PREFIX + identifier;
            Object tokensObj = redisTemplate.opsForHash().get(key, "tokens");
            if (tokensObj == null)
                return config.getMaxRequests();
            return Math.max(0, Long.parseLong(tokensObj.toString()));
        } catch (RedisConnectionFailureException ex) {
            log.warn("[Redis] Connection failed — using fallback getRemainingRequests for: {}", identifier);
            return fallback.getRemainingRequests(identifier, config);
        }
    }

    @Override
    public long getResetTimeEpochSeconds(String identifier, RateLimitConfig config) {
        // Redis keys expire after windowSeconds — reset time ≈ now + windowSeconds
        return System.currentTimeMillis() / 1000 + config.getWindowSeconds();
    }

    @Override
    public String getAlgorithmName() {
        return "REDIS_TOKEN_BUCKET";
    }
}
