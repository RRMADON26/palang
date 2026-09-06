package io.github.rrmadon26.palang.core;

/**
 * The outcome of attempting to claim an idempotency key.
 *
 * <p>Exactly one caller receives {@link Claimed} for a given key while that key is
 * live. Every other caller receives {@link InFlight}, {@link Completed}, or
 * {@link Conflict}, and must not execute the guarded work.
 */
public sealed interface ClaimResult {

    /** The key was free and is now held by this caller, which should execute the work. */
    record Claimed(String key) implements ClaimResult {}

    /**
     * Another caller holds the key and has not finished yet. The duplicate should
     * wait for the original to complete, or be rejected, depending on configuration.
     */
    record InFlight(String key) implements ClaimResult {}

    /** The key already carries a completed response, which should be replayed verbatim. */
    record Completed(StoredResponse response) implements ClaimResult {}

    /**
     * The key is in use with a different request payload. This signals a client bug:
     * the same idempotency key is being reused for genuinely different work.
     */
    record Conflict(String key, String storedFingerprint, String presentedFingerprint)
            implements ClaimResult {}
}
