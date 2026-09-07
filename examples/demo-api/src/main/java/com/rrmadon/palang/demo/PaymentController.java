package com.rrmadon.palang.demo;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The exact scenario from the README's hero example: a payment endpoint that does
 * nothing idempotency-aware itself. {@code executions} exists only so the
 * walkthrough can prove the handler really ran once — a real endpoint would not
 * need it.
 */
@RestController
public class PaymentController {

    private final AtomicInteger executions = new AtomicInteger();

    @PostMapping("/payments")
    public ResponseEntity<Map<String, Object>> charge(@RequestBody Map<String, Object> request) {
        int n = executions.incrementAndGet();
        return ResponseEntity.status(201).body(Map.of(
                "paymentId", "pay_" + n,
                "amount", request.getOrDefault("amount", 0),
                "status", "captured"
        ));
    }

    @GetMapping("/payments/executions")
    public Map<String, Object> executions() {
        return Map.of("count", executions.get());
    }
}
