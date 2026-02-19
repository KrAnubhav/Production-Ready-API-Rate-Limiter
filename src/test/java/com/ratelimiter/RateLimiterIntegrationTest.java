package com.ratelimiter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the Rate Limiter.
 * Uses H2 in-memory DB (test profile).
 * Tests the full request flow: filter → service → strategy.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class RateLimiterIntegrationTest {

        @Autowired
        private MockMvc mockMvc;

        // Default limit in test application.yml is 5
        private static final int DEFAULT_LIMIT = 5;

        @Test
        @DisplayName("1. First N requests succeed, (N+1)th gets HTTP 429")
        void testFirstNRequestsSucceedThenNextFails() throws Exception {
                String userId = "integration-user-1";

                // First 5 requests should succeed
                for (int i = 0; i < DEFAULT_LIMIT; i++) {
                        mockMvc.perform(post("/api/v1/request")
                                        .header("X-User-Id", userId))
                                        .andExpect(status().isOk());
                }

                // 6th request should be rejected with 429
                mockMvc.perform(post("/api/v1/request")
                                .header("X-User-Id", userId))
                                .andExpect(status().isTooManyRequests())
                                .andExpect(jsonPath("$.status").value(429))
                                .andExpect(jsonPath("$.message").exists())
                                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
        }

        @Test
        @DisplayName("2. Rate limit headers present on every response")
        void testRateLimitHeadersPresentOnAllResponses() throws Exception {
                String userId = "integration-user-2";

                MvcResult result = mockMvc.perform(post("/api/v1/request")
                                .header("X-User-Id", userId))
                                .andExpect(status().isOk())
                                .andReturn();

                assertThat(result.getResponse().getHeader("X-RateLimit-Limit")).isNotNull();
                assertThat(result.getResponse().getHeader("X-RateLimit-Remaining")).isNotNull();
                assertThat(result.getResponse().getHeader("X-RateLimit-Reset")).isNotNull();
        }

        @Test
        @DisplayName("3. Retry-After header present on 429 response")
        void testRetryAfterHeaderOnRateLimitExceeded() throws Exception {
                String userId = "integration-user-3";

                // Exhaust limit
                for (int i = 0; i < DEFAULT_LIMIT; i++) {
                        mockMvc.perform(post("/api/v1/request").header("X-User-Id", userId));
                }

                MvcResult result = mockMvc.perform(post("/api/v1/request")
                                .header("X-User-Id", userId))
                                .andExpect(status().isTooManyRequests())
                                .andReturn();

                assertThat(result.getResponse().getHeader("Retry-After")).isNotNull();
        }

        @Test
        @DisplayName("4. Admin reset endpoint clears the rate limit")
        void testResetEndpointClearsLimit() throws Exception {
                String userId = "integration-user-4";

                // Exhaust limit
                for (int i = 0; i < DEFAULT_LIMIT; i++) {
                        mockMvc.perform(post("/api/v1/request").header("X-User-Id", userId));
                }
                mockMvc.perform(post("/api/v1/request").header("X-User-Id", userId))
                                .andExpect(status().isTooManyRequests());

                // Reset via admin endpoint
                mockMvc.perform(post("/admin/reset/" + userId))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("success"));

                // Next request should succeed
                mockMvc.perform(post("/api/v1/request")
                                .header("X-User-Id", userId))
                                .andExpect(status().isOk());
        }

        @Test
        @DisplayName("5. Different identifiers are tracked separately")
        void testDifferentIdentifiersTrackedSeparately() throws Exception {
                String userA = "integration-userA";
                String userB = "integration-userB";

                // Exhaust userA's limit
                for (int i = 0; i < DEFAULT_LIMIT; i++) {
                        mockMvc.perform(post("/api/v1/request").header("X-User-Id", userA));
                }
                mockMvc.perform(post("/api/v1/request").header("X-User-Id", userA))
                                .andExpect(status().isTooManyRequests());

                // userB should still be allowed
                mockMvc.perform(post("/api/v1/request")
                                .header("X-User-Id", userB))
                                .andExpect(status().isOk());
        }

        @Test
        @DisplayName("6. Status endpoint returns correct rate limit info")
        void testStatusEndpointReturnsCorrectInfo() throws Exception {
                String userId = "integration-user-6";

                mockMvc.perform(get("/api/v1/status")
                                .header("X-User-Id", userId))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.identifier").value(userId))
                                .andExpect(jsonPath("$.totalLimit").value(DEFAULT_LIMIT))
                                .andExpect(jsonPath("$.tokensRemaining").isNumber())
                                .andExpect(jsonPath("$.algorithm").isString());
        }

        @Test
        @DisplayName("7. X-RateLimit-Remaining decrements with each request")
        void testRemainingDecrementsWithEachRequest() throws Exception {
                String userId = "integration-user-7";

                MvcResult first = mockMvc.perform(post("/api/v1/request")
                                .header("X-User-Id", userId))
                                .andExpect(status().isOk())
                                .andReturn();

                MvcResult second = mockMvc.perform(post("/api/v1/request")
                                .header("X-User-Id", userId))
                                .andExpect(status().isOk())
                                .andReturn();

                int remainingAfterFirst = Integer.parseInt(
                                first.getResponse().getHeader("X-RateLimit-Remaining"));
                int remainingAfterSecond = Integer.parseInt(
                                second.getResponse().getHeader("X-RateLimit-Remaining"));

                assertThat(remainingAfterSecond).isLessThan(remainingAfterFirst);
        }
}
