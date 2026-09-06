package com.rrmadon.palang.idempotency;

import com.rrmadon.palang.testkit.ConcurrentCallers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The distributed guarantee, proven against a real Redis.
 *
 * <p>A mock would confirm whatever semantics we imagined. Only a real server proves
 * the Lua claim script is genuinely indivisible when callers overlap.
 *
 * <p>Point this at any Redis:
 * <pre>{@code docker run -d -p 6379:6379 redis:7-alpine}</pre>
 * Override with {@code -Dpalang.test.redis.host} and {@code -Dpalang.test.redis.port}.
 * When no Redis is reachable the class is skipped rather than failing, so the build
 * stays usable without Docker. CI supplies Redis as a service container, so the
 * distributed path is always exercised before anything is released.
 */
@SpringBootTest(classes = TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "palang.idempotency.store=redis")
@EnabledIf("redisIsReachable")
class RedisIdempotencyIntegrationTest {

    private static final String BODY = "{\"amount\":50000}";

    static String redisHost() {
        return System.getProperty("palang.test.redis.host", "127.0.0.1");
    }

    static int redisPort() {
        return Integer.parseInt(System.getProperty("palang.test.redis.port", "6379"));
    }

    /**
     * Probes the configured Redis.
     *
     * <p>On a developer machine without Redis the class is skipped, so the build stays
     * usable. In CI that would be a silent hole: a suite that quietly runs nothing
     * still reports green. Setting {@code PALANG_REQUIRE_REDIS=true} turns an
     * unreachable Redis into a hard failure, so the distributed guarantee cannot be
     * released untested.
     *
     * @return true when a TCP connection succeeds within one second
     */
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
        registry.add("spring.data.redis.host", RedisIdempotencyIntegrationTest::redisHost);
        registry.add("spring.data.redis.port", RedisIdempotencyIntegrationTest::redisPort);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TestApplication.PaymentController controller;

    @BeforeEach
    void setUp() {
        controller.reset();
    }

    @Test
    @DisplayName("a duplicate is replayed from Redis, not re-executed")
    void duplicateReplaysFromRedis() {
        String key = "redis-" + System.nanoTime();

        ResponseEntity<String> first = post(key, BODY);
        ResponseEntity<String> second = post(key, BODY);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(second.getHeaders().getFirst(IdempotencyFilter.REPLAY_HEADER)).isEqualTo("true");
        assertThat(controller.executions()).isEqualTo(1);
    }

    @Test
    @DisplayName("a key reused with a different payload conflicts across the shared store")
    void reusedKeyConflicts() {
        String key = "redis-conflict-" + System.nanoTime();

        post(key, BODY);
        ResponseEntity<String> conflict = post(key, "{\"amount\":1}");

        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(conflict.getBody()).contains("key-reused");
        assertThat(controller.executions()).isEqualTo(1);
    }

    @Test
    @DisplayName("binary-safe replay: the stored body survives a Redis round trip byte for byte")
    void replayIsByteIdentical() {
        String key = "redis-bytes-" + System.nanoTime();

        ResponseEntity<String> first = post(key, BODY);
        ResponseEntity<String> replayed = post(key, BODY);

        assertThat(replayed.getBody()).isNotNull();
        assertThat(replayed.getBody().getBytes()).isEqualTo(first.getBody().getBytes());
    }

    @RepeatedTest(3)
    @DisplayName("32 simultaneous callers against Redis produce exactly one execution")
    void oneExecutionAcrossSharedStore() throws Exception {
        String key = "redis-burst-" + System.nanoTime();

        ConcurrentCallers.CallerResults<Integer> results = ConcurrentCallers.of(32)
                .fire(() -> post(key, BODY).getStatusCode().value())
                .assertNoFailures();

        assertThat(results.results()).hasSize(32);
        assertThat(controller.executions())
                .as("Redis must serialise the claim across all callers")
                .isEqualTo(1);
        assertThat(results.count(status -> status == 201)).isEqualTo(1);
        assertThat(results.count(status -> status == 409)).isEqualTo(31);
    }

    private ResponseEntity<String> post(String key, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        return rest.exchange("/payments", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
