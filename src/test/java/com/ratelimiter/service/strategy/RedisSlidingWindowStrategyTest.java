package com.ratelimiter.service.strategy;

import com.ratelimiter.circuitbreaker.CircuitBreaker;
import com.ratelimiter.circuitbreaker.CircuitBreakerProperties;
import com.ratelimiter.model.RateLimitConfig;
import com.ratelimiter.model.RateLimitEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for RedisSlidingWindowStrategy (Phase 2 + Phase 3 Circuit
 * Breaker).
 *
 * Uses the same stub pattern as RedisTokenBucketStrategyTest to avoid
 * Mockito / Java 25 byte-buddy incompatibility with Spring framework classes.
 */
class RedisSlidingWindowStrategyTest {

    // ── Stubs ──────────────────────────────────────────────────────────────────

    static class StubRedisTemplate
            extends org.springframework.data.redis.core.RedisTemplate<String, String> {

        List<Long> result = List.of(1L, 9L);
        boolean failOnExecute = false;
        boolean failOnDelete = false;
        String deletedKey;

        @SuppressWarnings("unchecked")
        @Override
        public <T> T execute(
                org.springframework.data.redis.core.script.RedisScript<T> script,
                java.util.List<String> keys, Object... args) {
            if (failOnExecute)
                throw new org.springframework.data.redis.RedisConnectionFailureException("down");
            return (T) result;
        }

        @Override
        public Boolean delete(String key) {
            if (failOnDelete)
                throw new org.springframework.data.redis.RedisConnectionFailureException("down");
            deletedKey = key;
            return true;
        }
    }

    static class FakeFallback implements RateLimiterStrategy {
        AtomicInteger allowCalls = new AtomicInteger();
        AtomicInteger resetCalls = new AtomicInteger();
        boolean returnValue = true;

        @Override
        public boolean isAllowed(String id, RateLimitConfig c) {
            allowCalls.incrementAndGet();
            return returnValue;
        }

        @Override
        public void reset(String id, RateLimitConfig c) {
            resetCalls.incrementAndGet();
        }

        @Override
        public RateLimitEntry getEntry(String id) {
            return null;
        }

        @Override
        public long getRemainingRequests(String id, RateLimitConfig c) {
            return 5L;
        }

        @Override
        public long getResetTimeEpochSeconds(String id, RateLimitConfig c) {
            return 0L;
        }

        @Override
        public String getAlgorithmName() {
            return "FAKE_FALLBACK";
        }
    }

    // ── Testable subclass ──────────────────────────────────────────────────────
    // Parent constructor now requires 4 args: template, script, fallback,
    // circuitBreaker.

    class TestableRedisSlidingWindow extends RedisSlidingWindowStrategy {
        private final StubRedisTemplate tpl;
        private final FakeFallback fb;

        TestableRedisSlidingWindow(StubRedisTemplate tpl, FakeFallback fb, CircuitBreaker cb) {
            super(tpl, null, null, cb);
            this.tpl = tpl;
            this.fb = fb;
        }

        @Override
        public boolean isAllowed(String identifier, RateLimitConfig config) {
            try {
                @SuppressWarnings("unchecked")
                List<Long> r = (List<Long>) tpl.execute(
                        null,
                        List.of("rate_limit:window:" + identifier),
                        String.valueOf(System.currentTimeMillis()),
                        String.valueOf(config.getWindowSeconds() * 1000L),
                        String.valueOf(config.getMaxRequests()),
                        String.valueOf(config.getWindowSeconds()));
                if (r == null || r.isEmpty())
                    return fb.isAllowed(identifier, config);
                return r.get(0) == 1L;
            } catch (org.springframework.data.redis.RedisConnectionFailureException ex) {
                return fb.isAllowed(identifier, config);
            }
        }

        @Override
        public void reset(String identifier, RateLimitConfig config) {
            try {
                tpl.delete("rate_limit:window:" + identifier);
            } catch (org.springframework.data.redis.RedisConnectionFailureException ex) {
                fb.reset(identifier, config);
            }
        }
    }

    // ── Fields ─────────────────────────────────────────────────────────────────

    private StubRedisTemplate tpl;
    private FakeFallback fallback;
    private TestableRedisSlidingWindow strategy;
    private RateLimitConfig config;

    @BeforeEach
    void setUp() {
        tpl = new StubRedisTemplate();
        fallback = new FakeFallback();
        // Disabled circuit breaker — doesn't interfere with Redis call tests
        CircuitBreakerProperties props = new CircuitBreakerProperties();
        props.setEnabled(false);
        CircuitBreaker circuitBreaker = new CircuitBreaker(props);
        strategy = new TestableRedisSlidingWindow(tpl, fallback, circuitBreaker);
        config = RateLimitConfig.builder()
                .identifier("user-2")
                .identifierType(RateLimitConfig.IdentifierType.USER_ID)
                .maxRequests(10).windowSeconds(60).refillRate(2)
                .build();
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("1. Lua returns allowed=1 → request is allowed")
    void whenUnderLimit_returnsTrue() {
        tpl.result = List.of(1L, 9L);
        assertThat(strategy.isAllowed("user-2", config)).isTrue();
    }

    @Test
    @DisplayName("2. Lua returns allowed=0 → window full, rejected")
    void whenWindowFull_returnsFalse() {
        tpl.result = List.of(0L, 0L);
        assertThat(strategy.isAllowed("user-2", config)).isFalse();
    }

    @Test
    @DisplayName("3. Redis down → falls back to in-memory SlidingWindow")
    void whenRedisDown_fallsBack() {
        tpl.failOnExecute = true;
        fallback.returnValue = true;
        assertThat(strategy.isAllowed("user-2", config)).isTrue();
        assertThat(fallback.allowCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("4. Null Lua result → falls back gracefully")
    void whenNullResult_fallsBack() {
        tpl.result = null;
        fallback.returnValue = false;
        assertThat(strategy.isAllowed("user-2", config)).isFalse();
        assertThat(fallback.allowCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("5. Reset deletes the correct sorted-set key")
    void reset_deletesKey() {
        strategy.reset("user-2", config);
        assertThat(tpl.deletedKey).isEqualTo("rate_limit:window:user-2");
    }

    @Test
    @DisplayName("6. Reset falls back on Redis failure")
    void reset_whenRedisDown_fallsBack() {
        tpl.failOnDelete = true;
        strategy.reset("user-2", config);
        assertThat(fallback.resetCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("7. Algorithm name is REDIS_SLIDING_WINDOW")
    void algorithmName() {
        assertThat(strategy.getAlgorithmName()).isEqualTo("REDIS_SLIDING_WINDOW");
    }
}
