# 🗓️ Sprint Overview — Production-Ready API Rate Limiter

> **Project:** Production-Ready API Rate Limiter  
> **Duration:** 6 Weeks | 3 Sprints (2 weeks each)  
> **Tech Stack:** Java 17 · Spring Boot 3 · Redis · PostgreSQL · Docker  
> **Total Points:** 100

---

## 📌 Sprint Structure

| Sprint | Duration | Phase | Focus | Points |
|--------|----------|-------|-------|--------|
| [Sprint 1](./sprint-1-core.md) | Week 1–2 | Phase 1 | Core Rate Limiter (Mandatory) | 40 pts |
| [Sprint 2](./sprint-2-redis.md) | Week 3–4 | Phase 2 | Distributed Rate Limiting with Redis | 15 pts |
| [Sprint 3](./sprint-3-circuit-breaker.md) | Week 5–6 | Phase 3 | Circuit Breaker + Final Submission | 10 pts |

**Documentation + Testing = 35 pts** (tracked across all sprints)

---

## 🏆 Chosen Options Strategy

```
Phase 1  →  Token Bucket + Sliding Window (BOTH) — already complete ✅
Phase 2  →  Option B: Distributed Rate Limiting with Redis
Phase 3  →  Option B: Resiliency & Circuit Breaker
Bonus    →  Prometheus + Grafana metrics
```

---

## 📊 Overall Scoring Tracker

| Category | Max Points | Status |
|----------|-----------|--------|
| Core: Algorithms (Token Bucket/Sliding Window) | 15 pts | ✅ Done |
| Core: Multiple User Support | 10 pts | ✅ Done |
| Core: Race Condition Handling | 10 pts | ✅ Done |
| Core: HTTP Headers & Status Codes | 5 pts | ✅ Done |
| Documentation: README | 5 pts | ✅ Done |
| Documentation: Architecture Diagram | 5 pts | ✅ Done |
| Documentation: Swagger/API Docs | 5 pts | ✅ Done |
| Testing: Unit Tests (5+) | 10 pts | ✅ Done |
| Testing: Integration Tests | 5 pts | ✅ Done |
| Testing: Code Organisation | 5 pts | ✅ Done |
| Phase 2: Redis Distributed Limiting | 15 pts | 🔲 Sprint 2 |
| Phase 3: Circuit Breaker | 10 pts | 🔲 Sprint 3 |
| **Total** | **100 pts** | **75/100 done** |

---

## 🔗 Sprint Documents

| File | Description |
|------|-------------|
| [sprint-1-core.md](./sprint-1-core.md) | Core rate limiter — Phase 1 retrospective + deliverables |
| [sprint-2-redis.md](./sprint-2-redis.md) | Redis distributed limiting — Phase 2 plan |
| [sprint-3-circuit-breaker.md](./sprint-3-circuit-breaker.md) | Circuit Breaker + final submission — Phase 3 plan |

---

*Updated: February 2026*
