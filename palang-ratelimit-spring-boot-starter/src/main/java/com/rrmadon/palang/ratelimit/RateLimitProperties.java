package com.rrmadon.palang.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Configuration for request-rate limiting. */
@ConfigurationProperties(prefix = "palang.rate-limit")
public class RateLimitProperties {

    /** Which backend holds bucket state. */
    public enum StoreType {
        /** Per-instance cache. Each replica enforces its own limit independently. */
        MEMORY,
        /** Shared Redis backend. Required for a limit that holds across replicas. */
        REDIS
    }

    private boolean enabled = true;

    private StoreType store = StoreType.MEMORY;

    /** Requests permitted per {@link #refillPeriod}. */
    private long capacity = 100;

    /** The window over which {@link #capacity} tokens are replenished. */
    private Duration refillPeriod = Duration.ofMinutes(1);

    /**
     * What happens when the store cannot be reached.
     *
     * <p>Deliberately the opposite default from Palang's idempotency module. An
     * idempotency store outage risks executing an operation twice, a correctness
     * failure, so that module fails the request. A rate limiter protects capacity,
     * not correctness — refusing every request because the limiter itself is down
     * turns a component meant to prevent an outage into the cause of one. Set this
     * to {@code false} only where staying under budget matters more than staying up,
     * for example a metered third-party API where every request has a direct cost.
     */
    private boolean failOpen = true;

    /**
     * How long an idle key's bucket is kept in the in-memory cache. Only applies to
     * {@link StoreType#MEMORY}. A client that returns after this window gets a fresh
     * bucket, which is a full reset rather than a continuation of their prior usage;
     * set this comfortably above {@link #refillPeriod} if that reset should be rare.
     */
    private Duration memoryIdleEviction = Duration.ofMinutes(10);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public StoreType getStore() {
        return store;
    }

    public void setStore(StoreType store) {
        this.store = store;
    }

    public long getCapacity() {
        return capacity;
    }

    public void setCapacity(long capacity) {
        this.capacity = capacity;
    }

    public Duration getRefillPeriod() {
        return refillPeriod;
    }

    public void setRefillPeriod(Duration refillPeriod) {
        this.refillPeriod = refillPeriod;
    }

    public boolean isFailOpen() {
        return failOpen;
    }

    public void setFailOpen(boolean failOpen) {
        this.failOpen = failOpen;
    }

    public Duration getMemoryIdleEviction() {
        return memoryIdleEviction;
    }

    public void setMemoryIdleEviction(Duration memoryIdleEviction) {
        this.memoryIdleEviction = memoryIdleEviction;
    }
}
