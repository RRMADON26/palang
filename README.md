# Palang

**Duplicate request suppression for Spring Boot.** A user taps "Pay" twice, a flaky
network makes the mobile client retry, or a webhook is redelivered — and the charge
happens twice. Palang makes the second request return the first one's response
instead of executing again.

```java
@PostMapping("/payments")
public Payment create(@RequestBody PaymentRequest request) {
    return payments.charge(request);   // unchanged
}
```

```yaml
palang:
  idempotency:
    store: redis
```

The client sends `Idempotency-Key: 6f1a-...`. The first request runs. Every later
request with that key gets the original response back, byte for byte, without the
controller being touched.

---

## Why this exists

Java has good answers for most cross-cutting concerns and no common answer for this
one. Teams write it per service, usually get the concurrent case wrong, and find out
in production.

| Concern | Use | Not this |
|---|---|---|
| Rate limiting | [Bucket4j](https://github.com/bucket4j/bucket4j) | — |
| Retry, circuit breaker | [Resilience4j](https://github.com/resilience4j/resilience4j) | — |
| Error responses | Spring 6 `ProblemDetail` | — |
| **Idempotent requests** | **Palang** | — |

Palang is deliberately narrow. It is not a gateway and does not try to replace the
libraries above; a later release will add thin, optional integrations for them under
one configuration namespace.

## Install

Releases are published through [JitPack](https://jitpack.io) while the Maven Central
namespace is being verified.

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.RRMADON26.palang</groupId>
  <artifactId>palang-idempotency-spring-boot-starter</artifactId>
  <version>v0.1.0</version>
</dependency>
```

Requires **Java 21** and **Spring Boot 3.3+**.

## What it guarantees

| Situation | Result |
|---|---|
| Same key, same payload, first call | Runs normally |
| Same key, same payload, later call | Original response replayed, `Idempotency-Replayed: true` |
| Same key, **different** payload | `409` — a client bug, not a duplicate |
| Same key, original still running | `409` with `Retry-After` |
| Original returned 5xx | Key released; the retry gets a real attempt |
| No key present | Passes through unguarded (configurable) |
| `GET`, `HEAD`, `DELETE` | Untouched |

The key alone is never trusted. Payloads are fingerprinted with SHA-256, so reusing
one key for two different requests is reported rather than silently answered with
the wrong response.

## Modules

| Module | Purpose |
|---|---|
| `palang-core` | Store and key-resolution SPI. No Spring dependency. |
| `palang-idempotency-spring-boot-starter` | Auto-configured servlet filter |
| `palang-testkit` | `MutableClock`, `ConcurrentCallers` |
| `palang-bom` | Version alignment |

## Choosing a store

`memory` (default) guards one JVM. With more than one replica, three duplicates can
land on three instances and all three execute — use `redis`, which moves the
decision into a store every replica shares.

Claiming a key in Redis is a Lua script, not `SETNX` followed by `GET`. Those two
calls are each atomic but not atomic together, and that gap is precisely where a
duplicate slips through.

## Testing your own code

`palang-testkit` ships the awkward parts:

```java
CallerResults<Integer> results = ConcurrentCallers.of(32)
        .fire(() -> post("/payments", body).getStatusCode().value());

results.assertNoFailures();
assertThat(results.count(status -> status == 201)).isEqualTo(1);
```

`ConcurrentCallers` holds every thread at a latch until all are ready, then releases
them together. Testing deduplication sequentially proves nothing — the failures that
matter only appear when callers genuinely overlap.

`MutableClock` advances time by hand so TTL behaviour is tested without sleeping.

## Documentation

- [Idempotency starter — full configuration and edge cases](palang-idempotency-spring-boot-starter/README.md)
- [Contributing](CONTRIBUTING.md)
- [Security policy](SECURITY.md)

## Status

`v0.1.0` — the idempotency module is complete and tested, including 32 concurrent
callers against a real Redis. The API may still change before `1.0`.

Planned: rate limiting, resilience presets, and correlation-ID propagation, each as
a thin integration over the established library rather than a reimplementation.

## Licence

[Apache 2.0](LICENSE)
