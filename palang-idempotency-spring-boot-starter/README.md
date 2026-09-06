# Idempotency starter

Duplicate request suppression as a servlet filter, auto-configured.

## Quick start

Add the dependency, then:

```yaml
palang:
  idempotency:
    store: redis        # 'memory' guards one instance only
```

Have clients send a key they generate once per logical operation and reuse across
retries:

```http
POST /payments
Idempotency-Key: 6f1a4c02-9b3e-4f21-8a77-2c1d9e5b0f88
Content-Type: application/json

{"amount": 50000}
```

Nothing in the controller changes.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `palang.idempotency.enabled` | `true` | Turns the filter off entirely |
| `palang.idempotency.store` | `memory` | `memory` or `redis` |
| `palang.idempotency.header-name` | `Idempotency-Key` | Header carrying the key |
| `palang.idempotency.ttl` | `24h` | How long a response stays replayable |
| `palang.idempotency.in-flight-ttl` | `5m` | How long a claim survives a dead request |
| `palang.idempotency.methods` | `POST, PATCH` | Guarded methods |
| `palang.idempotency.require-key` | `false` | Reject guarded requests that arrive with no key |
| `palang.idempotency.max-body-bytes` | `1048576` | Largest body buffered for fingerprinting |
| `palang.idempotency.cacheable-statuses` | `200, 201, 202, 204` | Which responses are worth replaying |

## Edge cases, stated plainly

**Redis is unreachable.** The filter propagates the failure rather than waving the
request through. Silently degrading to "no protection" is the wrong default for
something guarding payments — you would not learn about it until the double charges
appeared. Set `store: memory` if you would rather have per-instance protection.

**The TTL expires while a duplicate is still retrying.** After `ttl` the key is
forgotten and the next request executes for real. Set `ttl` longer than the longest
retry window your clients use; 24 hours suits most mobile clients.

**A request dies mid-flight.** Pod killed, connection dropped, JVM gone. The claim
carries `in-flight-ttl` (5 minutes) separate from the response TTL, so the key frees
itself. Without that split, one crash would lock a user out of that operation for a
full day.

**The response is 5xx.** Not cached. The key is released so the client's retry gets
a genuine second attempt — otherwise a transient upstream failure would be replayed
for 24 hours after the upstream recovered.

**Two different requests share one key.** Returns `409` with problem type
`key-reused`. Payloads are fingerprinted, so this is detected rather than answered
with the wrong response.

**A duplicate arrives while the original is still running.** Returns `409` with
`Retry-After: 1` instead of queueing. Queueing would hold a server thread per
duplicate; a burst of 32 would pin 31 threads waiting.

**The body is larger than `max-body-bytes`.** Returns `413`. Fingerprinting requires
buffering the body, so an unbounded upload would otherwise be an easy way to exhaust
heap through the filter.

**Responses are replayed without hop-by-hop headers.** `Transfer-Encoding`,
`Connection`, `Keep-Alive`, `Content-Length`, `Date` and `Server` describe one
transfer, not the payload. Replaying a captured `Transfer-Encoding: chunked`
alongside a fresh `Content-Length` leaves clients waiting for a terminating chunk
that never arrives.

## Problem responses

Failures use `application/problem+json` with a stable `type`:

| Type | Status | Cause |
|---|---|---|
| `.../problems/key-reused` | 409 | Same key, different payload |
| `.../problems/in-flight` | 409 | Original still executing |
| `.../problems/missing-key` | 400 | `require-key` is on and no key was sent |
| `.../problems/body-too-large` | 413 | Body exceeds `max-body-bytes` |

## Extending

Every bean is `@ConditionalOnMissingBean`. Supply your own to override.

Keying on the authenticated user rather than a client header:

```java
@Bean
KeyResolver<HttpServletRequest> idempotencyKeyResolver() {
    return request -> Optional.ofNullable(request.getUserPrincipal())
            .map(principal -> principal.getName() + ":" + request.getRequestURI());
}
```

The filter runs at order `0`, after Spring Security's chain, so the authenticated
principal is available.

## Running the tests

The distributed suite needs a Redis:

```bash
docker run -d -p 6379:6379 redis:7-alpine
mvn verify
```

Without one it is skipped. In CI, `PALANG_REQUIRE_REDIS=true` turns an unreachable
Redis into a failure, so the distributed guarantee is never released untested.
