package io.github.rrmadon26.palang.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/** Configuration for duplicate request suppression. */
@ConfigurationProperties(prefix = "palang.idempotency")
public class IdempotencyProperties {

    /** Which backing store holds keys and replayed responses. */
    public enum StoreType {
        /** Per-instance map. Guards one replica only. */
        MEMORY,
        /** Shared Redis store. Required when more than one replica is running. */
        REDIS
    }

    private boolean enabled = true;

    /** Request header carrying the client-supplied key. */
    private String headerName = "Idempotency-Key";

    /** How long a completed response stays replayable. */
    private Duration ttl = Duration.ofHours(24);

    /**
     * How long a claim survives before another caller may take it. Bounds the damage
     * when a request dies mid-flight instead of holding the key for the full TTL.
     */
    private Duration inFlightTtl = Duration.ofMinutes(5);

    private StoreType store = StoreType.MEMORY;

    /** HTTP methods that are guarded. Safe methods are pointless to guard. */
    private Set<String> methods = new LinkedHashSet<>(Set.of("POST", "PATCH"));

    /**
     * Reject a guarded request that arrives with no key. When false, such requests
     * pass through unguarded.
     */
    private boolean requireKey = false;

    /**
     * Largest request body that will be buffered for fingerprinting. Bodies above
     * this size are rejected with 413 rather than silently left unguarded.
     */
    private int maxBodyBytes = 1024 * 1024;

    /**
     * Response statuses worth replaying. A failure is normally not replayed, so the
     * client's retry gets a real second attempt.
     */
    private Set<Integer> cacheableStatuses = new LinkedHashSet<>(Set.of(200, 201, 202, 204));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public Duration getInFlightTtl() {
        return inFlightTtl;
    }

    public void setInFlightTtl(Duration inFlightTtl) {
        this.inFlightTtl = inFlightTtl;
    }

    public StoreType getStore() {
        return store;
    }

    public void setStore(StoreType store) {
        this.store = store;
    }

    public Set<String> getMethods() {
        return methods;
    }

    public void setMethods(Set<String> methods) {
        this.methods = methods;
    }

    public boolean isRequireKey() {
        return requireKey;
    }

    public void setRequireKey(boolean requireKey) {
        this.requireKey = requireKey;
    }

    public int getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public void setMaxBodyBytes(int maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    public Set<Integer> getCacheableStatuses() {
        return cacheableStatuses;
    }

    public void setCacheableStatuses(Set<Integer> cacheableStatuses) {
        this.cacheableStatuses = cacheableStatuses;
    }
}
