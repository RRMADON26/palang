package com.rrmadon.palang.ratelimit;

import com.rrmadon.palang.testkit.ConcurrentCallers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sequential requests cannot show whether the bucket is actually thread-safe under
 * a shared key; a burst can. This nails down that a fixed capacity is never
 * exceeded even when every caller arrives at the same instant.
 */
@SpringBootTest(classes = TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "palang.rate-limit.capacity=10",
        "palang.rate-limit.refill-period=1h"
})
class RateLimitConcurrencyTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TestApplication.PingController controller;

    @RepeatedTest(3)
    @DisplayName("32 simultaneous callers against a capacity-10 bucket: exactly 10 succeed")
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    // Without this, JUnit's context caching reuses one Spring context — and one
    // Caffeine-cached bucket — across all three repetitions. The bucket would still
    // be empty from repetition 1 by the time repetition 2 starts, since the refill
    // period here is an hour; every repetition needs a bucket that starts full.
    void capacityHoldsUnderConcurrency() throws Exception {
        controller.reset();

        ConcurrentCallers.CallerResults<Integer> results = ConcurrentCallers.of(32)
                .fire(() -> rest.getForEntity("/ping", String.class).getStatusCode().value())
                .assertNoFailures();

        assertThat(results.results()).hasSize(32);
        assertThat(results.count(status -> status == 200)).isEqualTo(10);
        assertThat(results.count(status -> status == 429)).isEqualTo(22);
        assertThat(controller.hits())
                .as("the controller must run exactly once per admitted request, never for a rejected one")
                .isEqualTo(10);
    }
}
