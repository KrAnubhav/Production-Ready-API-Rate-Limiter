package com.ratelimiter.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.scripting.support.ResourceScriptSource;

import java.util.List;

/**
 * Redis configuration for Phase 2 — Distributed Rate Limiting.
 *
 * Only activated when rate-limiter.use-redis=true (i.e., NOT in local profile).
 *
 * Provides:
 * - RedisTemplate<String, String> : for key-value operations
 * - tokenBucketScript : Lua script for atomic token bucket
 * - slidingWindowScript : Lua script for atomic sliding window
 */
@Configuration
@ConditionalOnProperty(name = "rate-limiter.use-redis", havingValue = "true")
public class RedisConfig {

    /**
     * RedisTemplate configured with String serializers for both keys and values.
     * Using String serialization keeps Redis keys human-readable in redis-cli.
     */
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

    /**
     * Lua script for Token Bucket algorithm.
     * Returns List<Long>: [allowed (1/0), remaining_tokens]
     */
    @Bean
    @SuppressWarnings("unchecked")
    public DefaultRedisScript<List<Long>> tokenBucketScript() {
        DefaultRedisScript<List<Long>> script = new DefaultRedisScript<>();
        script.setScriptSource(
                new ResourceScriptSource(new ClassPathResource("scripts/token_bucket.lua")));
        script.setResultType((Class<List<Long>>) (Class<?>) List.class);
        return script;
    }

    /**
     * Lua script for Sliding Window algorithm.
     * Returns List<Long>: [allowed (1/0), remaining_requests]
     */
    @Bean
    @SuppressWarnings("unchecked")
    public DefaultRedisScript<List<Long>> slidingWindowScript() {
        DefaultRedisScript<List<Long>> script = new DefaultRedisScript<>();
        script.setScriptSource(
                new ResourceScriptSource(new ClassPathResource("scripts/sliding_window.lua")));
        script.setResultType((Class<List<Long>>) (Class<?>) List.class);
        return script;
    }
}
