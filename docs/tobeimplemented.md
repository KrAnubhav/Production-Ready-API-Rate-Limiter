# 📋 Project Gap Analysis — Features To Be Implemented

Based on the **"Internship Technical Assessment final document.pdf"**, the following features are **missing from the current codebase** and need to be implemented.

---

## ✅ What's Already Done

| Feature | PDF Section | Status |
|---|---|---|
| Token Bucket (in-memory) | Phase 1 | ✅ Done |
| Sliding Window (in-memory) | Phase 1 | ✅ Done |
| Redis Token Bucket | Phase 2 | ✅ Done |
| Redis Sliding Window | Phase 2 | ✅ Done |
| Circuit Breaker (Redis protection) | Phase 3 | ✅ Done |
| Health Indicator | Phase 3 | ✅ Done |
| Prometheus Metrics | Phase 3 | ✅ Done |
| Admin Endpoints | Phase 3 | ✅ Done |

---

## ❌ What's NOT Yet Implemented

| Feature | PDF Section | Priority |
|---|---|---|
| Advanced Rules Engine | §4.3.3 | 🔴 High |
| Whitelist/Blacklist Management | §4.4.3 | 🔴 High |
| Cost-Based Rate Limiting | §4.4.4 | 🟡 Medium |
| User-Level Auto-Blocking Circuit Breaker | §4.4.2 | 🟡 Medium |
| Local Caching with Caffeine | Performance | 🔴 High |
| Async Processing | Performance | 🟡 Medium |
| Analytics Dashboard | §4.3.4 | 🟡 Medium |
| Performance Benchmarking | §4.1.1.3 | 🟢 Low |

---

## 🔴 1. Advanced Rules Engine (§4.3.3 — Option C)

**PDF Requirement:** Implement complex rate limiting rules beyond simple per-user limits.

### Features Required:
- **Endpoint-specific limits:**
  - `POST /api/upload` → 5 requests/minute
  - `GET /api/users` → 100 requests/hour
  - `GET /api/status` → Unlimited (no rate limit)

- **Time-Based Rules:**
  - Peak Hours (9AM–5PM): 50 requests/hour
  - Off-Peak (5PM–9AM): 200 requests/hour
  - Weekend: Unlimited

- **Burst Allowance:**
  - Normal: 100 requests/hour
  - Burst: Allow up to 120 requests in 5 minutes
  - Then: Throttle back to hourly limit

- **Rule Priority:** Higher priority rules evaluated first

### Testing Criteria:
- Create rule: `/api/search` limited to 10/sec
- Create rule: `/api/upload` limited to 5/min
- Verify each endpoint enforces its own limit
- Test time-based rules by changing system time
- Test rule priority (higher priority evaluated first)

---

## 🔴 2. Whitelist/Blacklist Management (§4.4.3 — Option C)

**PDF Requirement:** Maintain lists of identifiers that bypass rate limits or are permanently blocked.

### Features Required:
- **Whitelist:** Identifiers that bypass ALL rate limits
- **Blacklist:** Identifiers that are permanently blocked
- CRUD APIs for list management
- Import/export lists (CSV/JSON)
- Audit log for list changes
- **Expiration support** (temporary whitelist/blacklist)

### Testing Criteria:
- Add identifier to whitelist → Verify unlimited requests allowed
- Add identifier to blacklist → Verify all requests rejected immediately
- Import CSV with 100 identifiers → Verify all imported correctly
- Export whitelist → Verify CSV format correct
- Set expiration → Wait for expiration → Verify auto-removal

---

## 🟡 3. Cost-Based Rate Limiting (§4.4.4 — Option D)

**PDF Requirement:** Different operations consume different "costs" from the user's quota.

### Concept:
Instead of all operations costing 1 request, assign costs based on resource usage. User has a point budget per time window.

### Examples:
| Endpoint | Cost |
|---|---|
| `GET /api/users/{id}` | 1 point (simple read) |
| `GET /api/search?query=...` | 5 points (complex query) |
| `POST /api/reports/generate` | 50 points (expensive operation) |
| `POST /api/ai/analyze` | 100 points (AI processing) |

**User Budget:** 1000 points per hour

This allows:
- 1000 simple reads, OR
- 200 search queries, OR
- 20 report generations, OR
- Any mix of operations

### Testing Criteria:
- User has 1000 points
- Call expensive operation (50 points) 20 times → Uses 1000 points
- 21st call rejected → Insufficient points
- Call cheap operation (1 point) after expensive ones → Verify cost tracked correctly
- Wait for window reset → Verify points restored

---

## 🟡 4. User-Level Auto-Blocking Circuit Breaker (§4.4.2 — Option B)

> ⚠️ **Note:** The existing Circuit Breaker in Phase 3 protects Redis infrastructure. This is a **different** feature — it blocks abusive users.

**PDF Requirement:** Automatically block abusive users temporarily, then gradually allow them back.

### Features Required:
- Detect users hitting rate limit **repeatedly**
- **Auto-block with escalating durations:**
  - 1st violation: blocked 5 minutes
  - 2nd violation: blocked 15 minutes
  - 3rd violation: blocked 1 hour
  - 4th+ violation: blocked 24 hours
- Gradual recovery: reduce block time if behavior improves
- Manual override (admin can unblock user)
- Notification when circuit opens

### Circuit States:
- `CLOSED`: Normal operation, rate limiting active
- `OPEN`: Identifier blocked entirely (all requests rejected immediately)
- `HALF_OPEN`: Testing recovery (allow limited requests)

### Testing Criteria:
- Hit rate limit 5 times → Verify auto-blocked for 5 minutes
- Hit rate limit 10 times → Verify escalation to 15 minutes
- Wait for block to expire → Verify HALF_OPEN state
- Manual unblock via admin endpoint → Verify immediate access

---

## 🔴 5. Local Caching with Caffeine (Performance Optimization)

**PDF Requirement:** Cache rate limit configs locally to reduce Redis/DB queries.

### Implementation:
```java
// Cache rate limit configs locally
Cache<String, RateLimitConfig> configCache = Caffeine.newBuilder()
    .maximumSize(10000)
    .expireAfterWrite(5, TimeUnit.MINUTES)
    .build();

// Reduce database queries
RateLimitConfig config = configCache.get(key,
    k -> configRepo.findByKey(k));
```

### Additional Optimizations:
- **Reduce Redis Round Trips:** Use Lua scripts for atomic operations
- **Pipeline multiple commands** in a single Redis call
- **Connection pooling** for Redis connections

---

## 🟡 6. Async Processing (Performance Optimization)

**PDF Requirement:** Non-blocking operations to reduce latency.

### Features Required:
- Non-blocking I/O for rate limit checks
- Async logging (don't wait for log writes to complete)
- Batch analytics writes (buffer and flush in bulk)

---

## 🟡 7. Analytics Dashboard (§4.3.4)

**PDF Requirement:** Real-time dashboard showing rate limiter analytics.

### Features Required:
- **Time Series Charts:**
  - Requests per minute/hour (line chart)
  - Rate limit violations over time
  - Success rate percentage
- **User Analysis:**
  - Top 10 users by request count
  - Top 10 users by rate limit violations
  - User behavior patterns
- **Endpoint Analysis:**
  - Most requested endpoints
  - Endpoints with highest rate limit hits

### Testing Criteria:
- Submit 1000 requests from various identifiers
- Verify dashboard updates in real-time
- Check top violators list accuracy
- Export data and verify completeness

---

## 🟢 8. Performance Benchmarking (§4.1.1.3)

**PDF Requirement:** Measure and document system performance under load.

### Benchmarking Results to Include:
- Throughput: Requests per second
- Latency percentiles: p50, p95, p99
- Before/after comparison with caching enabled
- Load testing with JMeter or similar tool

---

## 📌 Recommended Implementation Order

1. **Local Caching (Caffeine)** — Quick win, measurable performance improvement
2. **Whitelist/Blacklist** — Straightforward, high visibility feature
3. **Advanced Rules Engine** — Demonstrates design complexity
4. **User-Level Auto-Blocking CB** — Extends existing CB concept
5. **Cost-Based Rate Limiting** — Interesting algorithmic challenge
6. **Async Processing** — Infrastructure improvement
7. **Analytics Dashboard** — Visualization layer
8. **Performance Benchmarking** — Final validation

---

*Last updated: 2026-02-20 | Based on: Internship Technical Assessment final document.pdf*
