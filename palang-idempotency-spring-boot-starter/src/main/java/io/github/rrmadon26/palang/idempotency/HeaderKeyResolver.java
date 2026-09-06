package io.github.rrmadon26.palang.idempotency;

import io.github.rrmadon26.palang.core.KeyResolver;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Objects;
import java.util.Optional;

/** Reads the idempotency key from a request header. */
public final class HeaderKeyResolver implements KeyResolver<HttpServletRequest> {

    private final String headerName;

    /**
     * Creates a resolver reading the given header.
     *
     * @param headerName the header carrying the key
     */
    public HeaderKeyResolver(String headerName) {
        this.headerName = Objects.requireNonNull(headerName, "headerName");
    }

    @Override
    public Optional<String> resolve(HttpServletRequest request) {
        String value = request.getHeader(headerName);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.trim());
    }
}
