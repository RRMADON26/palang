package io.github.rrmadon26.palang.testkit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * A clock you move by hand, so time-dependent behaviour can be tested without
 * sleeping.
 *
 * <p>Sleeping in tests trades wall-clock seconds for flakiness on a loaded CI
 * machine. Advancing a clock is instant and exact.
 *
 * <pre>{@code
 * MutableClock clock = MutableClock.at("2026-09-06T13:00:00Z");
 * IdempotencyStore store = new InMemoryIdempotencyStore(clock, Duration.ofMinutes(5));
 * store.claim("key", "fp", Duration.ofHours(24));
 * clock.advance(Duration.ofHours(25));
 * // the key is claimable again
 * }</pre>
 */
public final class MutableClock extends Clock {

    private final ZoneId zone;
    private volatile Instant instant;

    private MutableClock(Instant start, ZoneId zone) {
        this.instant = Objects.requireNonNull(start, "start");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /**
     * Creates a clock fixed at the given instant, in UTC.
     *
     * @param start the starting instant
     * @return a movable clock
     */
    public static MutableClock at(Instant start) {
        return new MutableClock(start, ZoneId.of("UTC"));
    }

    /**
     * Creates a clock fixed at the given ISO-8601 instant, in UTC.
     *
     * @param isoInstant an instant such as {@code 2026-09-06T13:00:00Z}
     * @return a movable clock
     */
    public static MutableClock at(String isoInstant) {
        return at(Instant.parse(isoInstant));
    }

    /**
     * Moves the clock forward.
     *
     * @param amount how far to move; must not be negative
     */
    public void advance(Duration amount) {
        if (amount.isNegative()) {
            throw new IllegalArgumentException("Cannot advance by a negative duration: " + amount);
        }
        this.instant = this.instant.plus(amount);
    }

    /**
     * Moves the clock to an exact instant, forwards or backwards.
     *
     * @param target the instant to sit at
     */
    public void set(Instant target) {
        this.instant = Objects.requireNonNull(target, "target");
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(instant, newZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public String toString() {
        return "MutableClock[" + instant + "]";
    }
}
