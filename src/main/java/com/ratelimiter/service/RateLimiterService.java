package com.ratelimiter.service;

import com.ratelimiter.config.RateLimiterProperties;
import com.ratelimiter.dto.RateLimitStatusResponse;
import com.ratelimiter.model.RateLimitConfig;
import com.ratelimiter.repository.RateLimitConfigRepository;
import com.ratelimiter.service.strategy.RateLimiterStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Core service that orchestrates rate limiting.
 *
 * Strategy selection (in order of priority):
 * 1. Redis Token Bucket — if use-redis=true AND algorithm=TOKEN_BUCKET
 * 2. Redis Sliding Window — if use-redis=true AND algorithm=SLIDING_WINDOW
 * 3. In-memory Token Bucket — if use-redis=false AND algorithm=TOKEN_BUCKET
 * (default)
 * 4. In-memory Sliding Window — if use-redis=false AND algorithm=SLIDING_WINDOW
 *
 * Redis strategies automatically fall back to in-memory on connection failure.
 */
@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);

    private final RateLimiterStrategy activeStrategy;
    private final RateLimitConfigRepository configRepository;
    private final RateLimiterProperties properties;

    public RateLimiterService(
            @Qualifier("tokenBucketStrategy") RateLimiterStrategy tokenBucket,
            @Qualifier("slidingWindowStrategy") RateLimiterStrategy slidingWindow,
            RateLimitConfigRepository configRepository,
            RateLimiterProperties properties,
            // Optional Redis strategies — null if use-redis=false (local profile)
            @Autowired(required = false) @Qualifier("redisTokenBucketStrategy") RateLimiterStrategy redisTokenBucket,
            @Autowired(required = false) @Qualifier("redisSlidingWindowStrategy") RateLimiterStrategy redisSlidingWindow) {
        this.configRepository = configRepository;
        this.properties = properties;

        boolean useRedis = properties.isUseRedis();
        String algorithm = properties.getAlgorithm();

        if (useRedis && "SLIDING_WINDOW".equalsIgnoreCase(algorithm) && redisSlidingWindow != null) {
            this.activeStrategy = redisSlidingWindow;
            log.info("Rate Limiter using: REDIS_SLIDING_WINDOW algorithm");
        } else if (useRedis && redisTokenBucket != null) {
            this.activeStrategy = redisTokenBucket;
            log.info("Rate Limiter using: REDIS_TOKEN_BUCKET algorithm");
        } else if ("SLIDING_WINDOW".equalsIgnoreCase(algorithm)) {
            this.activeStrategy = slidingWindow;
            log.info("Rate Limiter using: SLIDING_WINDOW algorithm (in-memory)");
        } else {
            this.activeStrategy = tokenBucket;
            log.info("Rate Limiter using: TOKEN_BUCKET algorithm (in-memory)");
        }
    }

    public boolean checkAndConsume(String identifier) {
        RateLimitConfig config = getConfigForIdentifier(identifier);
        boolean allowed = activeStrategy.isAllowed(identifier, config);
        if (!allowed) {
            log.warn("Rate limit exceeded for identifier: {}", identifier);
        }
        return allowed;
    }

    public void reset(String identifier) {
        RateLimitConfig config = getConfigForIdentifier(identifier);
        activeStrategy.reset(identifier, config);
        log.info("Rate limit reset for identifier: {}", identifier);
    }

    public RateLimitStatusResponse getStatus(String identifier) {
        RateLimitConfig config = getConfigForIdentifier(identifier);
        return RateLimitStatusResponse.builder()
                .identifier(identifier)
                .tokensRemaining(activeStrategy.getRemainingRequests(identifier, config))
                .totalLimit(config.getMaxRequests())
                .windowSeconds(config.getWindowSeconds())
                .resetAtEpochSeconds(activeStrategy.getResetTimeEpochSeconds(identifier, config))
                .algorithm(activeStrategy.getAlgorithmName())
                .build();
    }

    public long getRemainingRequests(String identifier) {
        RateLimitConfig config = getConfigForIdentifier(identifier);
        return activeStrategy.getRemainingRequests(identifier, config);
    }

    public long getResetTimeEpochSeconds(String identifier) {
        RateLimitConfig config = getConfigForIdentifier(identifier);
        return activeStrategy.getResetTimeEpochSeconds(identifier, config);
    }

    public long getLimit(String identifier) {
        return getConfigForIdentifier(identifier).getMaxRequests();
    }

    /**
     * Returns the name of the currently active rate limiting algorithm (e.g.
     * REDIS_TOKEN_BUCKET).
     */
    public String getActiveAlgorithmName() {
        return activeStrategy.getAlgorithmName();
    }

    public RateLimitConfig getConfigForIdentifier(String identifier) {
        return configRepository.findByIdentifier(identifier)
                .orElseGet(() -> buildDefaultConfig(identifier));
    }

    private RateLimitConfig buildDefaultConfig(String identifier) {
        return RateLimitConfig.builder()
                .identifier(identifier)
                .identifierType(RateLimitConfig.IdentifierType.USER_ID)
                .maxRequests(properties.getDefaultLimit())
                .windowSeconds(properties.getDefaultWindowSeconds())
                .refillRate(properties.getDefaultRefillRate())
                .build();
    }
}
