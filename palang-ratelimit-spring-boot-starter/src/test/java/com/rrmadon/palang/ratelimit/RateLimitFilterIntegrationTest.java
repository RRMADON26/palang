package com.rrmadon.palang.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The client key here always resolves to the loopback address, so every request
 * in this class shares one bucket across the whole Spring context — this is
 * exercised as a single continuous sequence rather than independent tests.
 */
@SpringBootTest(classes = TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "palang.rate-limit.capacity=5",
        "palang.rate-limit.refill-period=1m"
})
class RateLimitFilterIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TestApplication.PingController controller;

    @Test
    @DisplayName("capacity 5: five requests succeed with decreasing remaining, the sixth is rejected")
    void exhaustingCapacityRejectsWithHeaders() {
        controller.reset();

        for (int expectedRemaining = 4; expectedRemaining >= 0; expectedRemaining--) {
            ResponseEntity<String> response = rest.getForEntity("/ping", String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getFirst("RateLimit-Limit")).isEqualTo("5");
            assertThat(response.getHeaders().getFirst("RateLimit-Remaining"))
                    .isEqualTo(String.valueOf(expectedRemaining));
        }

        ResponseEntity<String> rejected = rest.getForEntity("/ping", String.class);

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getHeaders().getFirst("RateLimit-Remaining")).isEqualTo("0");
        assertThat(rejected.getHeaders().getFirst("Retry-After")).isNotNull();
        assertThat(rejected.getBody()).contains("rate-limit-exceeded");
        assertThat(controller.hits())
                .as("the 6th, rejected request must never reach the controller")
                .isEqualTo(5);
    }
}
