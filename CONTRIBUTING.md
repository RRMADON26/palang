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

## Commit messages

Explain why the change is correct, not what the diff shows. A reviewer can read the
diff; they cannot read the reasoning that produced it.
