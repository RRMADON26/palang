package com.rrmadon.palang.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Runs both Palang starters against real HTTP traffic. See
 * {@code examples/demo-api/README.md} for the walkthrough — curl commands and
 * what each one should print.
 */
@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
