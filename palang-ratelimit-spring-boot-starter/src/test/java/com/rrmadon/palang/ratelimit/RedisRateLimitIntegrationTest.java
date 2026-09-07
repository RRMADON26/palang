package com.rrmadon.palang.ratelimit;

import com.rrmadon.palang.testkit.ConcurrentCallers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The distributed guarantee, proven against a real Redis rather than a mock —
 * same reasoning as the idempotency module's Redis suite: a mock would confirm
 * whatever semantics were assumed, not whether Bucket4j's Lettuce integration is
 * genuinely atomic under concurrent access to a shared bucket.
 */
@SpringBootTest(classes = {TestApplication.class, RedisRateLimitIntegrationTest.UniqueKeyConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "palang.rate-limit.store=redis",
        "palang.rate-limit.capacity=10",
        "palang.rate-limit.refill-period=1h"
})
@EnabledIf("redisIsReachable")
class RedisRateLimitIntegrationTest {

    /**
     * Every request within one Spring context shares this bean, so all callers in a
     * single test still land on one bucket. {@code @DirtiesContext} rebuilds the
     * context — and this bean, with a fresh random key — for each test method and
     * each {@code @RepeatedTest} repetition, so no two runs can ever collide on the
     * same Redis key even though capacity and refill-period never let a bucket
     * recover on its own within a test.
     */
    @TestConfiguration
    static class UniqueKeyConfig {
        @Bean
        RateLimitKeyResolver rateLimitKeyResolver() {
            String key = "redis-ratelimit-test-" + UUID.randomUUID();
            return request -> key;
        }
    }

    static String redisHost() {
        return System.getProperty("palang.test.redis.host", "127.0.0.1");
    }

    static int redisPort() {
        return Integer.parseInt(System.getProperty("palang.test.redis.port", "6379"));
    }

    static boolean redisIsReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(redisHost(), redisPort()), 1000);
            return true;
        } catch (IOException e) {
            if (Boolean.parseBoolean(System.getenv("PALANG_REQUIRE_REDIS"))) {
                throw new IllegalStateException(
                        "PALANG_REQUIRE_REDIS is set but no Redis is reachable at "
                                + redisHost() + ":" + redisPort()
                                + ". Refusing to skip the distributed test suite.", e);
            }
            return false;
        }
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", RedisRateLimitIntegrationTest::redisHost);
        registry.add("spring.data.redis.port", RedisRateLimitIntegrationTest::redisPort);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TestApplication.PingController controller;

    @Test
    @DisplayName("capacity holds across the Redis-backed bucket")
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void capacityEnforcedViaRedis() {
        controller.reset();

        for (int i = 0; i < 10; i++) {
            ResponseEntity<String> ok = rest.getForEntity("/ping", String.class);
            assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<String> rejected = rest.getForEntity("/ping", String.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @RepeatedTest(3)
    @DisplayName("32 simultaneous callers against a Redis-backed capacity-10 bucket: exactly 10 succeed")
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void capacityHoldsUnderConcurrencyViaRedis() throws Exception {
        controller.reset();

        ConcurrentCallers.CallerResults<Integer> results = ConcurrentCallers.of(32)
                .fire(() -> rest.getForEntity("/ping", String.class).getStatusCode().value())
                .assertNoFailures();

        assertThat(results.results()).hasSize(32);
        assertThat(results.count(status -> status == 200))
                .as("Redis must serialise bucket consumption across all callers")
                .isEqualTo(10);
        assertThat(results.count(status -> status == 429)).isEqualTo(22);
        assertThat(controller.hits()).isEqualTo(10);
    }
}
