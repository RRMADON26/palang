package com.rrmadon.palang.idempotency.store;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rrmadon.palang.core.ClaimResult;
import com.rrmadon.palang.core.IdempotencyStore;
import com.rrmadon.palang.core.StoredResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Shared {@link IdempotencyStore} backed by Redis, for deployments running more
 * than one replica.
 *
 * <p>An in-memory store guards a single JVM only: with three pods behind a load
 * balancer, three duplicates can land on three pods and all three execute. This
 * store moves the decision into Redis so the guarantee holds across the fleet.
 *
 * <p>Claiming is a Lua script rather than a {@code SETNX} followed by a {@code GET}.
 * Those two calls are separately atomic but not atomic together, which is exactly
 * the window a duplicate slips through. Redis runs a script to completion without
 * interleaving other commands, so the read-and-maybe-write is genuinely indivisible.
 *
 * <p>Values are stored with a one character discriminator: {@code P} marks a claim
 * in flight and carries the payload fingerprint, {@code D} marks a completed
 * response and carries JSON. Keeping the discriminator outside the payload lets the
 * Lua scripts branch on state without parsing JSON inside Redis.
 */
public final class RedisIdempotencyStore implements IdempotencyStore {

    private static final String PENDING = "P";
    private static final String DONE = "D";
    private static final String CLAIMED_REPLY = "CLAIMED";
    private static final String DEFAULT_PREFIX = "palang:idem:";

    private static final RedisScript<String> CLAIM_SCRIPT = new DefaultRedisScript<>("""
            local existing = redis.call('GET', KEYS[1])
            if existing == false then
              redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
              return 'CLAIMED'
            end
            return existing
            """, String.class);

    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            local existing = redis.call('GET', KEYS[1])
            if existing ~= false and string.sub(existing, 1, 1) == 'P' then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration inFlightTtl;
    private final String keyPrefix;

    /**
     * Creates a store using the default {@code palang:idem:} key prefix.
     *
     * @param redis        template addressing the shared Redis
     * @param objectMapper used to serialise stored responses
     * @param clock        source of time
     * @param inFlightTtl  how long a claim survives before another caller may take it
     */
    public RedisIdempotencyStore(StringRedisTemplate redis,
                                 ObjectMapper objectMapper,
                                 Clock clock,
                                 Duration inFlightTtl) {
        this(redis, objectMapper, clock, inFlightTtl, DEFAULT_PREFIX);
    }

    /**
     * Creates a store with an explicit key prefix, so several applications can share
     * one Redis without colliding.
     *
     * @param redis        template addressing the shared Redis
     * @param objectMapper used to serialise stored responses
     * @param clock        source of time
     * @param inFlightTtl  how long a claim survives before another caller may take it
     * @param keyPrefix    namespace applied to every key
     */
    public RedisIdempotencyStore(StringRedisTemplate redis,
                                 ObjectMapper objectMapper,
                                 Clock clock,
                                 Duration inFlightTtl,
                                 String keyPrefix) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.inFlightTtl = Objects.requireNonNull(inFlightTtl, "inFlightTtl");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
    }

    @Override
    public ClaimResult claim(String key, String fingerprint, Duration ttl) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(ttl, "ttl");

        String reply = redis.execute(
                CLAIM_SCRIPT,
                List.of(namespaced(key)),
                PENDING + fingerprint,
                String.valueOf(inFlightTtl.toMillis()));

        if (reply == null || CLAIMED_REPLY.equals(reply)) {
            return new ClaimResult.Claimed(key);
        }

        String discriminator = reply.substring(0, 1);
        String payload = reply.substring(1);

        if (PENDING.equals(discriminator)) {
            return payload.equals(fingerprint)
                    ? new ClaimResult.InFlight(key)
                    : new ClaimResult.Conflict(key, payload, fingerprint);
        }

        Entry entry = decode(payload);
        return entry.fingerprint().equals(fingerprint)
                ? new ClaimResult.Completed(entry.toStoredResponse())
                : new ClaimResult.Conflict(key, entry.fingerprint(), fingerprint);
    }

    @Override
    public void complete(String key, StoredResponse response, Duration ttl) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(ttl, "ttl");
        redis.opsForValue().set(namespaced(key), DONE + encode(response), ttl);
    }

    @Override
    public void release(String key) {
        Objects.requireNonNull(key, "key");
        redis.execute(RELEASE_SCRIPT, List.of(namespaced(key)));
    }

    @Override
    public Optional<StoredResponse> find(String key) {
        Objects.requireNonNull(key, "key");
        String value = redis.opsForValue().get(namespaced(key));
        if (value == null || !value.startsWith(DONE)) {
            return Optional.empty();
        }
        return Optional.of(decode(value.substring(1)).toStoredResponse());
    }

    private String namespaced(String key) {
        return keyPrefix + key;
    }

    private String encode(StoredResponse response) {
        Entry entry = new Entry(
                response.status(),
                response.headers(),
                Base64.getEncoder().encodeToString(response.body()),
                response.fingerprint(),
                response.createdAt().toString());
        try {
            return objectMapper.writeValueAsString(entry);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise response for replay", e);
        }
    }

    private Entry decode(String json) {
        try {
            return objectMapper.readValue(json, Entry.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read stored response; the value is corrupt", e);
        }
    }

    /** Wire format for a completed response. Bodies are base64 so binary survives JSON. */
    record Entry(
            @JsonProperty("status") int status,
            @JsonProperty("headers") Map<String, List<String>> headers,
            @JsonProperty("body") String body,
            @JsonProperty("fingerprint") String fingerprint,
            @JsonProperty("createdAt") String createdAt) {

        @JsonCreator
        Entry {
        }

        StoredResponse toStoredResponse() {
            return new StoredResponse(
                    status,
                    headers,
                    Base64.getDecoder().decode(body),
                    fingerprint,
                    Instant.parse(createdAt));
        }
    }
}
