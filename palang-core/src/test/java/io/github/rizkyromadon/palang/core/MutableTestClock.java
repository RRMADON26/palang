package io.github.rizkyromadon.palang.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** Minimal advanceable clock so TTL behaviour is testable without sleeping. */
public final class MutableTestClock extends Clock {

    private final ZoneId zone;
    private Instant instant;

    public MutableTestClock(Instant start) {
        this(start, ZoneId.of("UTC"));
    }

    private MutableTestClock(Instant start, ZoneId zone) {
        this.instant = start;
        this.zone = zone;
    }

    public void advance(Duration amount) {
        this.instant = this.instant.plus(amount);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableTestClock(instant, newZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
