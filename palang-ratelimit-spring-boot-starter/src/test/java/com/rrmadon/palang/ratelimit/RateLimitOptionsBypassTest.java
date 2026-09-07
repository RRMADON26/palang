package com.rrmadon.palang.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A CORS preflight must succeed structurally for a browser to attempt the real
 * request at all. If OPTIONS consumed budget from the same bucket, a cross-origin
 * client would find every request silently refused by its own browser the moment
 * the caller is rate limited — the actual request would never even be sent.
 */
@SpringBootTest(classes = TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "palang.rate-limit.capacity=1",
        "palang.rate-limit.refill-period=1h"
})
class RateLimitOptionsBypassTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("OPTIONS requests never consume the bucket, even once it is exhausted")
    void optionsBypassesTheLimiterEntirely() {
        // Exhaust the one-token bucket with a real request.
        assertThat(rest.getForEntity("/ping", String.class).getStatusCodeValue()).isEqualTo(200);
        assertThat(rest.getForEntity("/ping", String.class).getStatusCodeValue()).isEqualTo(429);

        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> preflight = rest.exchange("/ping", HttpMethod.OPTIONS, null, String.class);
            assertThat(preflight.getHeaders().getFirst("RateLimit-Remaining")).isNull();
        }
    }
}
