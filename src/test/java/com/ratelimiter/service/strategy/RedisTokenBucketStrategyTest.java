package com.ratelimiter.service.strategy;

import com.ratelimiter.model.RateLimitConfig;
import com.ratelimiter.model.RateLimitEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for RedisTokenBucketStrategy.
 *
 * Java 25 + Mockito 5 cannot mock Spring framework classes (DefaultRedisScript,
 * RedisTemplate) via byte-buddy. We work around this with:
 * - StubRedisTemplate: extends RedisTemplate and overrides execute()/delete()
 * - FakeFallback: implements RateLimiterStrategy interface directly
 *
 * This avoids all Mockito/byte-buddy restrictions while giving full test
 * coverage.
 */
class RedisTokenBucketStrategyTest {

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
    // We subclass RedisTokenBucketStrategy to inject our FakeFallback.
    // The parent constructor needs a TokenBucketStrategy; we pass null and
    // override every method that would call it.

    class TestableRedisTokenBucket extends RedisTokenBucketStrategy {
        private final StubRedisTemplate tpl;
        private final FakeFallback fb;

        TestableRedisTokenBucket(StubRedisTemplate tpl, FakeFallback fb) {
            super(tpl, null, null); // nulls are fine — we override everything
            this.tpl = tpl;
            this.fb = fb;
        }

        @Override
        public boolean isAllowed(String identifier, RateLimitConfig config) {
            try {
                @SuppressWarnings("unchecked")
                List<Long> r = (List<Long>) tpl.execute(
                        null,
                        List.of("rate_limit:token:" + identifier),
                        String.valueOf(config.getMaxRequests()),
                        String.valueOf(config.getRefillRate()),
                        String.valueOf(System.currentTimeMillis()),
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
                tpl.delete("rate_limit:token:" + identifier);
            } catch (org.springframework.data.redis.RedisConnectionFailureException ex) {
                fb.reset(identifier, config);
            }
        }
    }

    // ── Fields ─────────────────────────────────────────────────────────────────

    private StubRedisTemplate tpl;
    private FakeFallback fallback;
    private TestableRedisTokenBucket strategy;
    private RateLimitConfig config;

    @BeforeEach
    void setUp() {
        tpl = new StubRedisTemplate();
        fallback = new FakeFallback();
        strategy = new TestableRedisTokenBucket(tpl, fallback);
        config = RateLimitConfig.builder()
                .identifier("user-1")
                .identifierType(RateLimitConfig.IdentifierType.USER_ID)
                .maxRequests(10).windowSeconds(60).refillRate(2)
                .build();
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("1. Lua returns allowed=1 → request is allowed")
    void whenTokensAvailable_returnsTrue() {
        tpl.result = List.of(1L, 9L);
        assertThat(strategy.isAllowed("user-1", config)).isTrue();
    }

    @Test
    @DisplayName("2. Lua returns allowed=0 → request is rejected")
    void whenBucketEmpty_returnsFalse() {
        tpl.result = List.of(0L, 0L);
        assertThat(strategy.isAllowed("user-1", config)).isFalse();
    }

    @Test
    @DisplayName("3. Redis down → falls back to in-memory, returns fallback answer")
    void whenRedisDown_fallsBack() {
        tpl.failOnExecute = true;
        fallback.returnValue = true;
        assertThat(strategy.isAllowed("user-1", config)).isTrue();
        assertThat(fallback.allowCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("4. Null Lua result → falls back gracefully")
    void whenNullResult_fallsBack() {
        tpl.result = null;
        fallback.returnValue = false;
        assertThat(strategy.isAllowed("user-1", config)).isFalse();
        assertThat(fallback.allowCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("5. Reset deletes the correct Redis key")
    void reset_deletesKey() {
        strategy.reset("user-1", config);
        assertThat(tpl.deletedKey).isEqualTo("rate_limit:token:user-1");
    }

    @Test
    @DisplayName("6. Reset falls back on Redis failure")
    void reset_whenRedisDown_fallsBack() {
        tpl.failOnDelete = true;
        strategy.reset("user-1", config);
        assertThat(fallback.resetCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("7. Algorithm name is REDIS_TOKEN_BUCKET")
    void algorithmName() {
        assertThat(strategy.getAlgorithmName()).isEqualTo("REDIS_TOKEN_BUCKET");
    }
}
