package com.rrmadon.palang.core.store;

import com.rrmadon.palang.core.ClaimResult;
import com.rrmadon.palang.core.IdempotencyStore;
import com.rrmadon.palang.core.StoredResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-JVM {@link IdempotencyStore} backed by a {@link ConcurrentHashMap}.
 *
 * <p>Claim atomicity comes from {@link ConcurrentHashMap#compute}, which holds the
 * bin lock for the key while the remapping function runs. Two threads presenting
 * the same key therefore cannot both be told they claimed it.
 *
 * <p>Suitable for a single instance or for tests. Deployments running more than one
 * replica need a shared store, otherwise each replica guards its own keys only.
 */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    private static final Duration DEFAULT_IN_FLIGHT_TTL = Duration.ofMinutes(5);

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration inFlightTtl;

    /**
     * Creates a store using the system clock and a five minute in-flight timeout.
     */
    public InMemoryIdempotencyStore() {
        this(Clock.systemUTC(), DEFAULT_IN_FLIGHT_TTL);
    }

    /**
     * Creates a store with an explicit clock and in-flight timeout.
     *
     * @param clock       source of time; inject a mutable clock in tests
     * @param inFlightTtl how long a claim survives before another caller may take it,
     *                    bounding the damage when a request dies mid-flight
     */
    public InMemoryIdempotencyStore(Clock clock, Duration inFlightTtl) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.inFlightTtl = Objects.requireNonNull(inFlightTtl, "inFlightTtl");
    }

    @Override
    public ClaimResult claim(String key, String fingerprint, Duration ttl) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(ttl, "ttl");

        Instant now = clock.instant();
        AtomicReference<ClaimResult> outcome = new AtomicReference<>();

        entries.compute(key, (k, existing) -> {
            if (existing == null || existing.isExpiredAt(now)) {
                outcome.set(new ClaimResult.Claimed(k));
                return new Pending(fingerprint, now.plus(inFlightTtl));
            }
            if (!existing.fingerprint().equals(fingerprint)) {
                outcome.set(new ClaimResult.Conflict(k, existing.fingerprint(), fingerprint));
                return existing;
            }
            if (existing instanceof Done done) {
                outcome.set(new ClaimResult.Completed(done.response()));
            } else {
                outcome.set(new ClaimResult.InFlight(k));
            }
            return existing;
        });

        return outcome.get();
    }

    @Override
    public void complete(String key, StoredResponse response, Duration ttl) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(ttl, "ttl");
        entries.put(key, new Done(response, clock.instant().plus(ttl)));
    }

    @Override
    public void release(String key) {
        Objects.requireNonNull(key, "key");
        entries.computeIfPresent(key, (k, existing) -> existing instanceof Pending ? null : existing);
    }

    @Override
    public Optional<StoredResponse> find(String key) {
        Objects.requireNonNull(key, "key");
        Entry entry = entries.get(key);
        if (entry instanceof Done done && !done.isExpiredAt(clock.instant())) {
            return Optional.of(done.response());
        }
        return Optional.empty();
    }

    /**
     * Drops every entry whose lifetime has elapsed.
     *
     * <p>Expired entries are also ignored on read, so calling this is an optimisation
     * for long-lived processes rather than a correctness requirement.
     *
     * @return the number of entries removed
     */
    public int purgeExpired() {
        Instant now = clock.instant();
        int before = entries.size();
        entries.values().removeIf(entry -> entry.isExpiredAt(now));
        return before - entries.size();
    }

    /**
     * Returns how many keys the store currently holds, expired or not.
     *
     * @return the entry count
     */
    public int size() {
        return entries.size();
    }

    private sealed interface Entry {
        String fingerprint();

        Instant expiresAt();

        default boolean isExpiredAt(Instant now) {
            return !expiresAt().isAfter(now);
        }
    }

    private record Pending(String fingerprint, Instant expiresAt) implements Entry {
    }

    private record Done(StoredResponse response, Instant expiresAt) implements Entry {
        @Override
        public String fingerprint() {
            return response.fingerprint();
        }
    }
}
