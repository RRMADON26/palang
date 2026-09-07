# Rate limit starter

Request-rate limiting as a servlet filter, auto-configured, built on
[Bucket4j](https://github.com/bucket4j/bucket4j)'s token-bucket algorithm.

## Quick start

```yaml
palang:
  rate-limit:
    capacity: 100
    refill-period: 1m
    store: redis        # 'memory' guards one instance only
```

That's it — no controller changes. A caller over the limit gets `429` with a
`Retry-After` header.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `palang.rate-limit.enabled` | `true` | Turns the filter off entirely |
| `palang.rate-limit.store` | `memory` | `memory` or `redis` |
| `palang.rate-limit.capacity` | `100` | Requests permitted per `refill-period` |
| `palang.rate-limit.refill-period` | `1m` | The window `capacity` is replenished over |
| `palang.rate-limit.fail-open` | `true` | What happens when the store is unreachable — see below |
| `palang.rate-limit.memory-idle-eviction` | `10m` | How long an idle key's bucket stays cached (`memory` only) |

This ships one global limit per key. Per-endpoint or per-plan limits are a natural
follow-up but are not built yet — ask in an issue if you need them sooner.

## Edge cases, stated plainly

**The store is unreachable.** This is where rate limiting and idempotency
deliberately disagree, and it is worth understanding why rather than copying a
default without reading it.

Palang's idempotency module fails a request when its store is down, because
letting the request through unguarded risks executing a payment twice — a
correctness failure. A rate limiter protects *capacity*, not correctness. Refusing
every request because the limiter's own backend is unreachable turns a component
meant to prevent an outage into the cause of one, which is worse than the
temporary absence of a limit. So `fail-open` defaults to `true`: on a backend error,
the request proceeds, unlimited, and a warning is logged.

Set `fail-open: false` only where staying under budget matters more than staying
up — for instance in front of a metered third-party API where every request has a
direct cost regardless of your own availability.

**The default key is the remote address.** Behind a reverse proxy or load
balancer, every request's remote address is the proxy's, not the client's, which
collapses every caller onto one shared bucket. Supply your own
`RateLimitKeyResolver` bean reading `X-Forwarded-For` — but only once the proxy is
configured to strip any such header a client sent directly, since otherwise a
client can forge the header and present a different claimed address on every
request to evade the limit entirely.

```java
@Bean
RateLimitKeyResolver rateLimitKeyResolver() {
    return request -> request.getHeader("X-Forwarded-For");
}
```

**An idle bucket in memory mode gets evicted, not preserved.** `memory-idle-eviction`
bounds the cache so a rate limiter tracking millions of distinct client IPs over a
process's lifetime does not grow without limit. The tradeoff: a client that returns
after that window gets a fresh, full bucket — a reset, not a continuation of their
prior usage. Set this comfortably above `refill-period` if that reset should be
rare, since a client usually re-appears well within one refill window anyway.

**Filter ordering, when the idempotency starter is also present.** The rate limit
filter runs at order `-1`, one step ahead of the idempotency filter's order `0`, so
an over-limit caller is rejected before an idempotency claim — which costs a store
round trip — is ever attempted for a request that would be discarded regardless.

## Extending

Both the bucket source and the key resolver are `@ConditionalOnMissingBean`.
Supplying a `RateLimitKeyResolver` (shown above) or a `RateLimiterSource` overrides
the default without needing to reimplement the filter.

## Running the tests

The distributed suite needs a Redis:

```bash
docker run -d -p 6379:6379 redis:7-alpine
./mvnw verify
```

Without one it is skipped. In CI, `PALANG_REQUIRE_REDIS=true` turns an unreachable
Redis into a failure, so the distributed guarantee is never released untested.
