package io.github.rrmadon26.palang.idempotency;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal application exercising the filter against a real controller. */
@SpringBootApplication
public class TestApplication {

    /** A payment-like endpoint that records how many times it actually ran. */
    @RestController
    public static class PaymentController {

        private final AtomicInteger executions = new AtomicInteger();

        @PostMapping("/payments")
        public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> request)
                throws InterruptedException {
            int run = executions.incrementAndGet();
            // Simulated work, wide enough for duplicates to arrive mid-flight.
            Thread.sleep(120);
            return ResponseEntity.status(201).body(Map.of(
                    "paymentId", "pay_" + run,
                    "amount", request.getOrDefault("amount", 0)));
        }

        @PostMapping("/always-fails")
        public ResponseEntity<Map<String, Object>> fail(@RequestBody Map<String, Object> request) {
            executions.incrementAndGet();
            return ResponseEntity.status(500).body(Map.of("error", "upstream unavailable"));
        }

        public int executions() {
            return executions.get();
        }

        public void reset() {
            executions.set(0);
        }
    }
}
