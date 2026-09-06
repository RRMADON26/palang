package com.rrmadon.palang.core;

import java.time.Duration;
import java.util.Optional;

/**
 * Storage for idempotency keys and the responses they guard.
 *
 * <p>Implementations must make {@link #claim} atomic with respect to concurrent
 * callers, including callers in different JVMs when the backing store is shared.
 * A store that allows two callers to both receive {@link ClaimResult.Claimed} for
 * one key does not satisfy this contract.
 */
public interface IdempotencyStore {

    /**
     * Attempts to take ownership of {@code key}.
     *
     * @param key         the idempotency key presented by the client
     * @param fingerprint fingerprint of the request payload, used to detect key reuse
     * @param ttl         how long a completed response remains replayable
     * @return the outcome; only {@link ClaimResult.Claimed} permits executing the work
     */
    ClaimResult claim(String key, String fingerprint, Duration ttl);

    /**
     * Records the response produced by the caller that held the claim, making it
     * available for replay until {@code ttl} elapses.
     *
     * @param key      the key held by this caller
     * @param response the response to replay for later duplicates
     * @param ttl      how long the response remains replayable
     */
    void complete(String key, StoredResponse response, Duration ttl);

    /**
     * Releases a claim without recording a response, so a later retry may proceed.
     * Called when the guarded work failed in a way that should not be replayed.
     *
     * @param key the key to release
     */
    void release(String key);

    /**
     * Looks up a completed response without claiming the key.
     *
     * @param key the key to look up
     * @return the stored response, or empty if the key is absent, in flight, or expired
     */
    Optional<StoredResponse> find(String key);
}
