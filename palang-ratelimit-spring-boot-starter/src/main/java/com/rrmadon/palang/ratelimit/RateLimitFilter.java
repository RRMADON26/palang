package com.rrmadon.palang.ratelimit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Rejects requests once a caller's token bucket is exhausted.
 *
 * <p>Runs ahead of every other Palang filter ({@link #FILTER_ORDER}): rejecting
 * over-limit traffic here means an idempotency claim, which costs a store
 * round-trip, is never attempted for a request that will not proceed anyway.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    /** Placed before the idempotency filter's order 0. */
    public static final int FILTER_ORDER = -1;

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final String PROBLEM_BASE = "https://github.com/rrmadon26/palang/problems/";

    private final RateLimiterSource source;
    private final RateLimitKeyResolver keyResolver;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Creates the filter.
     *
     * @param source       supplies the bucket for a resolved key
     * @param keyResolver  derives the identity a limit is enforced against
     * @param properties   behaviour configuration
     * @param objectMapper renders problem responses
     * @param clock        used only to render {@code RateLimit-Reset} as an epoch second
     */
    public RateLimitFilter(RateLimiterSource source,
                           RateLimitKeyResolver keyResolver,
                           RateLimitProperties properties,
                           ObjectMapper objectMapper,
                           Clock clock) {
        this.source = Objects.requireNonNull(source, "source");
        this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String key;
        ConsumptionProbe probe;
        try {
            key = keyResolver.resolve(request);
            Bucket bucket = source.bucketFor(key);
            probe = bucket.tryConsumeAndReturnRemaining(1);
        } catch (RuntimeException e) {
            if (properties.isFailOpen()) {
                log.warn("Rate limit backend unavailable; allowing the request through "
                        + "because palang.rate-limit.fail-open is true", e);
                chain.doFilter(request, response);
            } else {
                log.error("Rate limit backend unavailable and fail-open is false; rejecting", e);
                writeProblem(response, HttpStatus.SERVICE_UNAVAILABLE, "backend-unavailable",
                        "Rate limiter unavailable",
                        "The rate limiting backend could not be reached and "
                                + "palang.rate-limit.fail-open is false.");
            }
            return;
        }

        response.setHeader("RateLimit-Limit", String.valueOf(properties.getCapacity()));

        if (probe.isConsumed()) {
            response.setHeader("RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            chain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
        response.setHeader("RateLimit-Remaining", "0");
        response.setHeader("RateLimit-Reset", String.valueOf(
                Instant.now(clock).plusSeconds(retryAfterSeconds).getEpochSecond()));
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));

        log.debug("Rate limit exceeded for key {}", key);
        writeProblem(response, HttpStatus.TOO_MANY_REQUESTS, "rate-limit-exceeded",
                "Rate limit exceeded",
                "This caller has exceeded " + properties.getCapacity() + " requests per "
                        + properties.getRefillPeriod() + ". Retry after " + retryAfterSeconds + "s.");
    }

    private void writeProblem(HttpServletResponse response,
                              HttpStatus status,
                              String type,
                              String title,
                              String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(PROBLEM_BASE + type));
        problem.setTitle(title);
        problem.setDetail(detail);

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
        response.flushBuffer();
    }
}
