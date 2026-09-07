# Palang demo

A real Spring Boot app wiring both starters together, for trying them by hand
before trusting a release.

## Run it

```bash
cd examples/demo-api
../../mvnw spring-boot:run
```

Starts on `:8080` by default. If that's taken, pass a different port:

```bash
../../mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=8089"
```

Open **http://localhost:8080** (or whichever port you picked) in a browser. The
app serves a small UI at `/` — buttons for sending a charge, replaying it,
forcing a conflict, and draining the rate limit, with a live activity log
underneath. Same-origin, no setup: it talks to the app it's served by.

Prefer the terminal, or want commands you can script and diff? Everything below
does the exact same thing with `curl`.

## Try idempotency

```bash
# First call: runs for real
curl -i -X POST localhost:8089/payments \
  -H "Idempotency-Key: order-abc" -H "Content-Type: application/json" \
  -d '{"amount":50000}'
# 201, paymentId: pay_1

# Same key, same body: replayed, not re-executed
curl -i -X POST localhost:8089/payments \
  -H "Idempotency-Key: order-abc" -H "Content-Type: application/json" \
  -d '{"amount":50000}'
# 201, same pay_1, header Idempotency-Replayed: true

# Same key, different body: a client bug, not a duplicate
curl -i -X POST localhost:8089/payments \
  -H "Idempotency-Key: order-abc" -H "Content-Type: application/json" \
  -d '{"amount":999999}'
# 409, type .../problems/key-reused

# Prove the handler really ran once
curl localhost:8089/payments/executions
# {"count":1}
```

## Try rate limiting

Configured deliberately low here — `capacity: 5` per minute — so the limit shows
up after a handful of requests instead of a hundred. The library's own default is
100/minute; see `palang-ratelimit-spring-boot-starter/README.md`.

```bash
for i in $(seq 1 7); do curl -i -s localhost:8089/payments/executions | head -1; done
```

The first several return `200`, then `429` with `Retry-After` and a
`RateLimit-Remaining: 0` header once the bucket is empty.

**One shared bucket, not one per endpoint.** The limit is per client, across every
route — the idempotency calls above and the rate-limit calls here draw from the
*same* bucket, because the rate limit filter runs ahead of everything else in the
chain and has no notion of "which endpoint." If you've already made a few calls to
`/payments` in this session, you'll hit `429` sooner than a clean count of five
would suggest. Restart the app, or wait out `refill-period`, for a clean slate.

## Try it against Redis

Needs a Redis reachable at `127.0.0.1:6379` by default:

```bash
../../mvnw spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=redis"
```

Already have something else on 6379 (as is common in a dev machine with several
projects running)? Point at a different one:

```bash
../../mvnw spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=redis --spring.data.redis.port=6399"
```

Run the same curl commands above from two different terminals hitting the same
port — with `redis` active, the limit and the idempotency replay hold across both,
proving the guarantee is genuinely shared rather than per-process.

## What this does not test

This app is for feeling out the behaviour by hand. The rigorous proof is the
automated suite — in particular the tests that fire dozens of callers at once and
assert an exact admitted count, which a manual curl session cannot reliably show
because of ordinary network jitter between calls. Run `../../mvnw test` from the
repo root to see those.
