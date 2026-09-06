package com.rrmadon.palang.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The test that decides whether this library is trustworthy.
 *
 * <p>A deduplication layer that passes sequential tests and then loses a race is
 * worse than no deduplication at all, because it is trusted. Thirty-two callers
 * fire at one endpoint simultaneously with an identical key; exactly one may reach
 * the controller.
 */
@SpringBootTest(classes = TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyConcurrencyTest {

    private static final int CALLERS = 32;
    private static final String BODY = "{\"amount\":50000}";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TestApplication.PaymentController controller;

    @BeforeEach
    void setUp() {
        controller.reset();
    }

    @RepeatedTest(3)
    @DisplayName("32 simultaneous callers with one key produce exactly one execution")
    void oneExecutionUnderConcurrency() throws Exception {
        String key = "burst-" + System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(CALLERS);
        CountDownLatch ready = new CountDownLatch(CALLERS);
        CountDownLatch fire = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CALLERS);
        ConcurrentLinkedQueue<Integer> statuses = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> bodies = new ConcurrentLinkedQueue<>();
        AtomicInteger transportErrors = new AtomicInteger();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        HttpEntity<String> request = new HttpEntity<>(BODY, headers);

        try {
            for (int i = 0; i < CALLERS; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        fire.await();
                        ResponseEntity<String> response =
                                rest.exchange("/payments", HttpMethod.POST, request, String.class);
                        statuses.add(response.getStatusCode().value());
                        if (response.getStatusCode().value() == 201) {
                            bodies.add(response.getBody());
                        }
                    } catch (Exception e) {
                        transportErrors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            fire.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(transportErrors.get()).as("transport errors").isZero();
        assertThat(statuses).hasSize(CALLERS);
        assertThat(controller.executions())
                .as("the controller must run exactly once for %d duplicate calls", CALLERS)
                .isEqualTo(1);
        assertThat(statuses).filteredOn(s -> s == 201)
                .as("exactly one caller is served the real response").hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409)
                .as("every duplicate is told the work is already in flight")
                .hasSize(CALLERS - 1);
        assertThat(bodies).allSatisfy(body -> assertThat(body).contains("pay_1"));
    }
}
