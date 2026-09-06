package io.github.rrmadon26.palang.idempotency;

import io.github.rrmadon26.palang.core.ClaimResult;
import io.github.rrmadon26.palang.core.IdempotencyStore;
import io.github.rrmadon26.palang.core.StoredResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens when the store is down.
 *
 * <p>The tempting behaviour is to wave the request through when Redis is
 * unreachable, so traffic keeps flowing. That is the wrong default for something
 * guarding payments: protection would disappear silently and nobody would find out
 * until the duplicate charges did. Failing loudly is a decision, and a decision
 * deserves a test rather than a sentence in the README.
 */
@SpringBootTest(classes = {TestApplication.class, StoreFailureTest.FailingStoreConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StoreFailureTest {

    @TestConfiguration
    static class FailingStoreConfig {
        @Bean
        IdempotencyStore failingStore() {
            return new IdempotencyStore() {
                @Override
                public ClaimResult claim(String key, String fingerprint, Duration ttl) {
                    throw new IllegalStateException("Redis is down");
                }

                @Override
                public void complete(String key, StoredResponse response, Duration ttl) {
                    throw new IllegalStateException("Redis is down");
                }

                @Override
                public void release(String key) {
                    throw new IllegalStateException("Redis is down");
                }

                @Override
                public Optional<StoredResponse> find(String key) {
                    throw new IllegalStateException("Redis is down");
                }
            };
        }
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
    @DisplayName("an unreachable store fails the request instead of executing it unguarded")
    void storeFailureDoesNotSilentlyDisableProtection() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "store-down");

        ResponseEntity<String> response = rest.exchange(
                "/payments", HttpMethod.POST,
                new HttpEntity<>("{\"amount\":50000}", headers), String.class);

        assertThat(response.getStatusCode().is5xxServerError())
                .as("the caller must be told the guarantee could not be applied")
                .isTrue();
        assertThat(controller.executions())
                .as("the payment must not run while unprotected")
                .isZero();
    }

    @Test
    @DisplayName("unguarded methods still work when the store is down")
    void unguardedRequestsAreUnaffected() {
        ResponseEntity<String> response = rest.exchange(
                "/payments", HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode().is5xxServerError()).isFalse();
    }
}
