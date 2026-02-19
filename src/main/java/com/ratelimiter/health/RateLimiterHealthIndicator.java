package com.ratelimiter.health;

import com.ratelimiter.circuitbreaker.CircuitBreaker;
import com.ratelimiter.circuitbreaker.CircuitBreakerState;
import com.ratelimiter.service.RateLimiterService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Custom Health Indicator for the Rate Limiter — Phase 3.
 *
 * Exposed at: GET /actuator/health
 *
 * States:
 * CLOSED → Health.up() — system nominal
 * OPEN → Health.down() — Redis unreliable, circuit tripped
 * HALF_OPEN → Health.unknown() — probing for recovery
 */
@Component("rateLimiter")
public class RateLimiterHealthIndicator implements HealthIndicator {

    private final CircuitBreaker circuitBreaker;
    private final RateLimiterService rateLimiterService;

    public RateLimiterHealthIndicator(CircuitBreaker circuitBreaker,
            RateLimiterService rateLimiterService) {
        this.circuitBreaker = circuitBreaker;
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    public Health health() {
        CircuitBreakerState cbState = circuitBreaker.getState();
        String algorithm = rateLimiterService.getActiveAlgorithmName();

        return switch (cbState) {
            case CLOSED -> Health.up()
                    .withDetail("circuit", "CLOSED")
                    .withDetail("algorithm", algorithm)
                    .withDetail("failureCount", circuitBreaker.getFailureCount())
                    .build();

            case OPEN -> Health.down()
                    .withDetail("circuit", "OPEN")
                    .withDetail("tripCount", circuitBreaker.getTripCount())
                    .withDetail("failOpen", circuitBreaker.isFailOpen())
                    .withDetail("openedAt", circuitBreaker.getOpenedAt())
                    .withDetail("algorithm", algorithm)
                    .build();

            case HALF_OPEN -> Health.unknown()
                    .withDetail("circuit", "HALF_OPEN")
                    .withDetail("tripCount", circuitBreaker.getTripCount())
                    .withDetail("algorithm", algorithm)
                    .build();
        };
    }
}
