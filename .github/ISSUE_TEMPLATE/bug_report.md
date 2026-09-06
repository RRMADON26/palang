---
name: Bug report
about: Something behaves differently from the documented semantics
labels: bug
---

**What happened**

**What you expected**

**Configuration**
```yaml
palang:
  idempotency:
```

**Environment**
- Palang version:
- Spring Boot version:
- Java version:
- Store: memory / redis
- Replicas: single / multiple

**Reproduction**

A failing test is the fastest path to a fix. If the problem involves duplicates
racing, `ConcurrentCallers` from `palang-testkit` reproduces overlap reliably.
