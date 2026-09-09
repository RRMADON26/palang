## What changed and why

<!-- The why matters more than the what — reviewers can already read the diff. -->

## Tests

- [ ] `./mvnw verify` passes locally
- [ ] New behaviour has a test; a concurrency-sensitive change has a test
      using `ConcurrentCallers` (from `palang-testkit`), not a sequential loop
- [ ] If this touches the Redis path, the distributed suite ran against a
      real Redis (`docker run -d -p 6379:6379 redis:7-alpine`), not just skipped

## Checklist

- [ ] Scoped to one change (see CONTRIBUTING.md)
- [ ] `palang-core` still has zero Spring dependency, if touched
- [ ] Doc comments / module README updated for any changed public behaviour
