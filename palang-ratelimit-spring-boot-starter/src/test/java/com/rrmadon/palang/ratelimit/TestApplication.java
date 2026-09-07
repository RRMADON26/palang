package com.rrmadon.palang.ratelimit;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.atomic.AtomicInteger;

/** Minimal application exercising the filter against a real endpoint. */
@SpringBootApplication
public class TestApplication {

    @RestController
    public static class PingController {
        private final AtomicInteger hits = new AtomicInteger();

        @GetMapping("/ping")
        public String ping() {
            return "pong " + hits.incrementAndGet();
        }

        public int hits() {
            return hits.get();
        }

        public void reset() {
            hits.set(0);
        }
    }
}
