package com.ratelimiter.controller;

import com.ratelimiter.circuitbreaker.CircuitBreaker;
import com.ratelimiter.dto.RateLimitStatusResponse;
import com.ratelimiter.model.RateLimitConfig;
import com.ratelimiter.repository.RateLimitConfigRepository;
import com.ratelimiter.service.RateLimiterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Admin endpoints for managing rate limits and circuit breaker.
 */
@RestController
@RequestMapping("/admin")
@Tag(name = "Admin API", description = "Admin endpoints for rate limit management and circuit breaker control")
public class AdminController {

    private final RateLimiterService rateLimiterService;
    private final RateLimitConfigRepository configRepository;
    private final CircuitBreaker circuitBreaker;

    public AdminController(RateLimiterService rateLimiterService,
            RateLimitConfigRepository configRepository,
            CircuitBreaker circuitBreaker) {
        this.rateLimiterService = rateLimiterService;
        this.configRepository = configRepository;
        this.circuitBreaker = circuitBreaker;
    }

    @PostMapping("/reset/{identifier}")
    @Operation(summary = "Reset rate limit for an identifier")
    public ResponseEntity<Map<String, String>> resetLimit(@PathVariable String identifier) {
        rateLimiterService.reset(identifier);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Rate limit reset for: " + identifier));
    }

    @GetMapping("/status/{identifier}")
    @Operation(summary = "Get rate limit status for any identifier")
    public ResponseEntity<RateLimitStatusResponse> getStatus(@PathVariable String identifier) {
        return ResponseEntity.ok(rateLimiterService.getStatus(identifier));
    }

    @GetMapping("/config")
    @Operation(summary = "List all rate limit configurations")
    public ResponseEntity<List<RateLimitConfig>> getAllConfigs() {
        return ResponseEntity.ok(configRepository.findAll());
    }

    @GetMapping("/config/{id}")
    @Operation(summary = "Get config by ID")
    public ResponseEntity<RateLimitConfig> getConfigById(@PathVariable Long id) {
        return configRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/config")
    @Operation(summary = "Create a custom rate limit config")
    public ResponseEntity<RateLimitConfig> createConfig(@RequestBody RateLimitConfig config) {
        return ResponseEntity.ok(configRepository.save(config));
    }

    @PutMapping("/config/{id}")
    @Operation(summary = "Update an existing rate limit config")
    public ResponseEntity<RateLimitConfig> updateConfig(@PathVariable Long id,
            @RequestBody RateLimitConfig updated) {
        return configRepository.findById(id)
                .map(existing -> {
                    existing.setMaxRequests(updated.getMaxRequests());
                    existing.setWindowSeconds(updated.getWindowSeconds());
                    existing.setRefillRate(updated.getRefillRate());
                    return ResponseEntity.ok(configRepository.save(existing));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/config/{id}")
    @Operation(summary = "Delete a rate limit config")
    public ResponseEntity<Map<String, String>> deleteConfig(@PathVariable Long id) {
        if (!configRepository.existsById(id))
            return ResponseEntity.notFound().build();
        configRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("status", "deleted", "id", String.valueOf(id)));
    }

    // ── Circuit Breaker Admin Endpoints (Phase 3) ────────────────────────────

    @GetMapping("/circuit-breaker/status")
    @Operation(summary = "Get circuit breaker status — state, trip count, failure count")
    public ResponseEntity<Map<String, Object>> getCircuitBreakerStatus() {
        var status = circuitBreaker.getStatus();
        return ResponseEntity.ok(Map.of(
                "state", status.getState().name(),
                "failureCount", status.getFailureCount(),
                "tripCount", status.getTripCount(),
                "openedAt", status.getOpenedAt() != null ? status.getOpenedAt() : "never",
                "openDurationSeconds", status.getOpenDurationSeconds(),
                "failOpen", status.isFailOpen(),
                "enabled", status.isEnabled()));
    }

    @PostMapping("/circuit-breaker/reset")
    @Operation(summary = "Manually reset circuit breaker to CLOSED state")
    public ResponseEntity<Map<String, String>> resetCircuitBreaker() {
        circuitBreaker.reset();
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Circuit breaker reset to CLOSED",
                "state", circuitBreaker.getState().name()));
    }

    @PostMapping("/circuit-breaker/trip")
    @Operation(summary = "Manually trip circuit breaker to OPEN state (for testing)")
    public ResponseEntity<Map<String, String>> tripCircuitBreaker() {
        circuitBreaker.trip();
        return ResponseEntity.ok(Map.of(
                "status", "tripped",
                "message", "Circuit breaker tripped to OPEN",
                "state", circuitBreaker.getState().name()));
    }
}
