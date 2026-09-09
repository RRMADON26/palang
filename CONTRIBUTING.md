# Contributing

## Building

Requires JDK 21.

```bash
./mvnw verify
```

The distributed test suite needs a Redis on `127.0.0.1:6379`:

```bash
docker run -d -p 6379:6379 redis:7-alpine
```

Override with `-Dpalang.test.redis.host` and `-Dpalang.test.redis.port`. Without a
Redis those tests skip; they do not fail.

## What a change needs

**Concurrency claims need concurrent tests.** Anything touching claim, release or
replay must be covered by a test where callers genuinely overlap. `ConcurrentCallers`
in `palang-testkit` holds threads at a latch and releases them together. Sequential
tests of a deduplication layer prove almost nothing.

**Failure paths are the feature.** Store unreachable, request dies mid-flight, TTL
expiring under a retry, non-cacheable status. These are where the value is, so they
need tests and a line in the module README's edge-case section.

**`palang-core` stays free of Spring.** It is the framework-agnostic layer and CI
asserts this. Framework code belongs in a starter.

**Time is injected, never read.** Use the `Clock` bean and `MutableClock` in tests.
No `Thread.sleep` to age something out — that trades wall-clock seconds for
flakiness on a loaded CI machine.

## Good first contributions

Looking for somewhere to start rather than scratching your own itch:

- **Resilience presets** — a thin `palang-resilience-spring-boot-starter` wrapping
  Resilience4j with sane defaults (retry + circuit breaker + timeout wired
  together), the same relationship this project already has with Bucket4j for
  rate limiting. Listed in the README roadmap, not yet built.
- **Correlation-ID propagation** — MDC integration and exception-mapping
  conventions over Spring 6's own `ProblemDetail`. Also on the roadmap.
- **Per-endpoint or per-plan rate limits** — the rate-limit starter currently
  ships one global bucket per client across every route, by design for v1 (see
  `palang-ratelimit-spring-boot-starter/README.md`). A `Limiter` keyed on
  `(identity, route)` is a real, requested extension.
- **A WebFlux equivalent** — both starters are servlet-based (`OncePerRequestFilter`).
  A reactive path for Spring WebFlux users is open territory.
- **A store or limiter backend you actually run** — Memcached, Hazelcast,
  whatever your production stack already has. `IdempotencyStore` and the
  rate-limit `RateLimiterSource` are the contract; open an issue first if
  you're unsure the atomicity requirement translates cleanly to your backend.
- **Benchmarks.** There are none yet — JMH coverage of the hot paths (`claim`,
  `Allow`) would catch a regression before a user does.

Not sure an idea fits the project's scope? Open an issue and ask before writing
code — faster for both of us than a PR that turns out to need a different shape.

## Commit messages and PRs

Explain why the change is correct, not what the diff shows. A reviewer can read the
diff; they cannot read the reasoning that produced it.

Keep PRs scoped to one change. A drive-by formatting fix bundled into a feature PR
makes both harder to review; send it separately.

## Code of conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md). Report
concerns to the maintainer via a private security advisory or the contact listed
there.
