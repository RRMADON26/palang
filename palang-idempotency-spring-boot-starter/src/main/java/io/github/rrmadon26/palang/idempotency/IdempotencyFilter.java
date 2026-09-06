package io.github.rrmadon26.palang.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rrmadon26.palang.core.ClaimResult;
import io.github.rrmadon26.palang.core.IdempotencyStore;
import io.github.rrmadon26.palang.core.KeyResolver;
import io.github.rrmadon26.palang.core.PayloadFingerprint;
import io.github.rrmadon26.palang.core.StoredResponse;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Suppresses duplicate requests carrying the same idempotency key, replaying the
 * original response instead of executing the work a second time.
 *
 * <p>The header the client sends is never trusted as a lock on its own: the request
 * payload is fingerprinted, so reusing one key for two different requests is
 * reported as a conflict rather than silently answered with the wrong response.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    /** Marks a response that was replayed rather than freshly executed. */
    public static final String REPLAY_HEADER = "Idempotency-Replayed";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final String PROBLEM_BASE = "https://github.com/RRMADON26/palang/problems/";

    /**
     * Headers that describe this connection or this transfer rather than the payload.
     *
     * <p>Replaying them corrupts the response. A captured {@code Transfer-Encoding:
     * chunked} re-emitted alongside a fixed {@code Content-Length} leaves the client
     * waiting for a terminating chunk that never arrives, which surfaces as a hang
     * and then a premature EOF. Framing is the container's job on every response,
     * including a replayed one.
     */
    private static final Set<String> NON_REPLAYABLE_HEADERS = Set.of(
            "connection",
            "keep-alive",
            "transfer-encoding",
            "content-length",
            "te",
            "trailer",
            "upgrade",
            "proxy-authenticate",
            "proxy-authorization",
            "date",
            "server");

    private final IdempotencyStore store;
    private final KeyResolver<HttpServletRequest> keyResolver;
    private final IdempotencyProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Creates the filter.
     *
     * @param store        where keys and replayable responses live
     * @param keyResolver  derives the key from the request
     * @param properties   behaviour configuration
     * @param objectMapper used to render problem responses
     * @param clock        source of time for stored response timestamps
     */
    public IdempotencyFilter(IdempotencyStore store,
                             KeyResolver<HttpServletRequest> keyResolver,
                             IdempotencyProperties properties,
                             ObjectMapper objectMapper,
                             Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.getMethods().contains(request.getMethod().toUpperCase(Locale.ROOT));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        Optional<String> resolved = keyResolver.resolve(request);
        if (resolved.isEmpty()) {
            if (properties.isRequireKey()) {
                writeProblem(response, HttpStatus.BAD_REQUEST, "missing-key",
                        "Idempotency key required",
                        "This endpoint requires a " + properties.getHeaderName() + " header.");
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        String key = resolved.get();
        byte[] body = request.getInputStream().readAllBytes();
        if (body.length > properties.getMaxBodyBytes()) {
            writeProblem(response, HttpStatus.PAYLOAD_TOO_LARGE, "body-too-large",
                    "Request body too large to guard",
                    "Body of " + body.length + " bytes exceeds the "
                            + properties.getMaxBodyBytes() + " byte limit for idempotent requests.");
            return;
        }

        CachedBodyHttpServletRequest buffered = new CachedBodyHttpServletRequest(request, body);
        String fingerprint = PayloadFingerprint.of(body);
        ClaimResult claim = store.claim(key, fingerprint, properties.getTtl());

        switch (claim) {
            case ClaimResult.Claimed ignored -> execute(buffered, response, chain, key, fingerprint);
            case ClaimResult.Completed completed -> replay(response, completed.response());
            case ClaimResult.InFlight ignored -> {
                log.debug("Key {} is still in flight; rejecting duplicate", key);
                response.setHeader("Retry-After", "1");
                writeProblem(response, HttpStatus.CONFLICT, "in-flight",
                        "Request already in progress",
                        "A request with this idempotency key is still being processed. Retry shortly.");
            }
            case ClaimResult.Conflict conflict -> {
                log.warn("Key {} reused with a different payload", key);
                writeProblem(response, HttpStatus.CONFLICT, "key-reused",
                        "Idempotency key reused with a different payload",
                        "This key was first used with a different request body. "
                                + "Use a fresh key for a different request.");
                log.debug("stored fingerprint {} vs presented {}",
                        conflict.storedFingerprint(), conflict.presentedFingerprint());
            }
        }
    }

    private void execute(CachedBodyHttpServletRequest request,
                         HttpServletResponse response,
                         FilterChain chain,
                         String key,
                         String fingerprint) throws ServletException, IOException {

        ResponseCapturingHttpServletResponse capturing =
                new ResponseCapturingHttpServletResponse(response);
        boolean stored = false;
        try {
            chain.doFilter(request, capturing);
            int status = capturing.getStatus();
            if (properties.getCacheableStatuses().contains(status)) {
                store.complete(key, new StoredResponse(
                        status,
                        headersOf(capturing),
                        capturing.capturedBody(),
                        fingerprint,
                        Instant.now(clock)), properties.getTtl());
                stored = true;
            }
        } finally {
            if (!stored) {
                // A failure or a non-cacheable status must not lock the key: the
                // client's retry deserves a real second attempt.
                store.release(key);
            }
            capturing.flushBuffer();
        }
    }

    private void replay(HttpServletResponse response, StoredResponse stored) throws IOException {
        response.reset();
        response.setStatus(stored.status());
        stored.headers().forEach((name, values) -> {
            if (isReplayable(name)) {
                values.forEach(value -> response.addHeader(name, value));
            }
        });
        response.setHeader(REPLAY_HEADER, "true");
        byte[] body = stored.body();
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }

    private static Map<String, List<String>> headersOf(HttpServletResponse response) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : response.getHeaderNames()) {
            if (isReplayable(name)) {
                headers.put(name, new ArrayList<>(response.getHeaders(name)));
            }
        }
        return headers;
    }

    private static boolean isReplayable(String headerName) {
        return !NON_REPLAYABLE_HEADERS.contains(headerName.toLowerCase(Locale.ROOT));
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
