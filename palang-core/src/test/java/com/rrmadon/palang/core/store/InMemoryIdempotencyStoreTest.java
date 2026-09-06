package com.rrmadon.palang.core.store;

import com.rrmadon.palang.core.ClaimResult;
import com.rrmadon.palang.core.MutableTestClock;
import com.rrmadon.palang.core.StoredResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryIdempotencyStoreTest {

    private static final String KEY = "order-9f3a";
    private static final String FINGERPRINT = "abc123";
    private static final Duration TTL = Duration.ofHours(24);

    private MutableTestClock clock;
    private InMemoryIdempotencyStore store;

    @BeforeEach
    void setUp() {
        clock = new MutableTestClock(Instant.parse("2026-09-06T13:00:00Z"));
        store = new InMemoryIdempotencyStore(clock, Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("first caller claims the key")
    void firstCallerClaims() {
        assertThat(store.claim(KEY, FINGERPRINT, TTL))
                .isInstanceOf(ClaimResult.Claimed.class);
    }

    @Test
    @DisplayName("second caller sees the work already in flight")
    void secondCallerSeesInFlight() {
        store.claim(KEY, FINGERPRINT, TTL);
        assertThat(store.claim(KEY, FINGERPRINT, TTL))
                .isInstanceOf(ClaimResult.InFlight.class);
    }

    @Test
    @DisplayName("once completed, duplicates replay the stored response")
    void completedClaimReplays() {
        store.claim(KEY, FINGERPRINT, TTL);
        store.complete(KEY, response(201, "{\"id\":42}"), TTL);

        ClaimResult result = store.claim(KEY, FINGERPRINT, TTL);

        assertThat(result).isInstanceOf(ClaimResult.Completed.class);
        StoredResponse replayed = ((ClaimResult.Completed) result).response();
        assertThat(replayed.status()).isEqualTo(201);
        assertThat(new String(replayed.body(), StandardCharsets.UTF_8)).isEqualTo("{\"id\":42}");
    }

    @Test
    @DisplayName("same key with a different payload is a conflict, not a replay")
    void differentPayloadConflicts() {
        store.claim(KEY, FINGERPRINT, TTL);
        store.complete(KEY, response(201, "{\"id\":42}"), TTL);

        ClaimResult result = store.claim(KEY, "a-different-fingerprint", TTL);

        assertThat(result).isInstanceOf(ClaimResult.Conflict.class);
        ClaimResult.Conflict conflict = (ClaimResult.Conflict) result;
        assertThat(conflict.storedFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(conflict.presentedFingerprint()).isEqualTo("a-different-fingerprint");
    }

    @Test
    @DisplayName("the key is claimable again once its TTL elapses")
    void expiredKeyIsClaimableAgain() {
        store.claim(KEY, FINGERPRINT, TTL);
        store.complete(KEY, response(200, "ok"), TTL);

        clock.advance(TTL.plusSeconds(1));

        assertThat(store.claim(KEY, FINGERPRINT, TTL))
                .isInstanceOf(ClaimResult.Claimed.class);
        assertThat(store.find(KEY)).isEmpty();
    }

    @Test
    @DisplayName("a claim abandoned past the in-flight timeout does not wedge the key")
    void abandonedClaimTimesOut() {
        store.claim(KEY, FINGERPRINT, TTL);

        clock.advance(Duration.ofMinutes(6));

        assertThat(store.claim(KEY, FINGERPRINT, TTL))
                .isInstanceOf(ClaimResult.Claimed.class);
    }

    @Test
    @DisplayName("releasing a claim lets a retry through")
    void releaseAllowsRetry() {
        store.claim(KEY, FINGERPRINT, TTL);
        store.release(KEY);

        assertThat(store.claim(KEY, FINGERPRINT, TTL))
                .isInstanceOf(ClaimResult.Claimed.class);
    }

    @Test
    @DisplayName("release does not discard an already completed response")
    void releaseKeepsCompletedResponse() {
        store.claim(KEY, FINGERPRINT, TTL);
        store.complete(KEY, response(200, "ok"), TTL);

        store.release(KEY);

        assertThat(store.find(KEY)).isPresent();
    }

    @RepeatedTest(3)
    @DisplayName("under 64 concurrent callers exactly one claim is granted")
    void exactlyOneClaimUnderConcurrency() throws Exception {
        int threads = 64;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<ClaimResult> results = new ConcurrentLinkedQueue<>();

        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        fire.await();
                        results.add(store.claim(KEY, FINGERPRINT, TTL));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            fire.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(results).hasSize(threads);
        assertThat(results).filteredOn(r -> r instanceof ClaimResult.Claimed).hasSize(1);
        assertThat(results).filteredOn(r -> r instanceof ClaimResult.InFlight).hasSize(threads - 1);
        assertThat(results).noneMatch(r -> r instanceof ClaimResult.Conflict);
    }

    private static StoredResponse response(int status, String body) {
        return new StoredResponse(
                status,
                Map.of("Content-Type", List.of("application/json")),
                body.getBytes(StandardCharsets.UTF_8),
                FINGERPRINT,
                clockInstant());
    }

    private static Instant clockInstant() {
        return Instant.parse("2026-09-06T13:00:00Z");
    }
}
