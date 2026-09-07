package com.rrmadon.palang.ratelimit;

import io.github.bucket4j.Bucket;

/**
 * Supplies the {@link Bucket} a key should be checked against.
 *
 * <p>Bucket4j already presents one {@code Bucket} interface for both a purely
 * local bucket and one proxied to a distributed backend, so this is the only
 * seam Palang needs between memory and Redis — there is no separate store
 * abstraction to maintain in parallel.
 */
@FunctionalInterface
public interface RateLimiterSource {

    /**
     * Returns the bucket for {@code key}, creating it under the configured limit if
     * it does not yet exist.
     *
     * @param key the rate limit identity, from a {@link RateLimitKeyResolver}
     * @return the bucket to consume a token from
     */
    Bucket bucketFor(String key);
}
