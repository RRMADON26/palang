package io.github.rrmadon26.palang.core;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A captured HTTP response, held so that a duplicate request carrying the same
 * idempotency key can be answered with a byte-identical replay.
 *
 * @param status      the HTTP status code of the original response
 * @param headers     response headers captured from the original response
 * @param body        the raw response body; defensively copied on construction and access
 * @param fingerprint fingerprint of the request payload that produced this response
 * @param createdAt   when the original response completed
 */
public record StoredResponse(
        int status,
        Map<String, List<String>> headers,
        byte[] body,
        String fingerprint,
        Instant createdAt) {

    public StoredResponse {
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(createdAt, "createdAt");
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        body = body.clone();
    }

    /**
     * Returns a copy of the response body. Callers may write to the returned array
     * without corrupting the stored copy.
     *
     * @return a fresh copy of the body bytes
     */
    @Override
    public byte[] body() {
        return body.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StoredResponse other)) {
            return false;
        }
        return status == other.status
                && headers.equals(other.headers)
                && Arrays.equals(body, other.body)
                && fingerprint.equals(other.fingerprint)
                && createdAt.equals(other.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, headers, Arrays.hashCode(body), fingerprint, createdAt);
    }

    @Override
    public String toString() {
        return "StoredResponse[status=" + status
                + ", bodyBytes=" + body.length
                + ", fingerprint=" + fingerprint
                + ", createdAt=" + createdAt + "]";
    }
}
