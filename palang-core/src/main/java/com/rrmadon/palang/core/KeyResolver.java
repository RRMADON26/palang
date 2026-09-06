package com.rrmadon.palang.core;

import java.util.Optional;

/**
 * Derives the idempotency key for a request.
 *
 * <p>Kept generic over the request type so that {@code palang-core} stays free of
 * any web framework. The Spring starter supplies a resolver over
 * {@code HttpServletRequest}.
 *
 * @param <R> the request type this resolver reads
 */
@FunctionalInterface
public interface KeyResolver<R> {

    /**
     * Resolves the key for a request.
     *
     * @param request the incoming request
     * @return the key, or empty when the request should not be guarded
     */
    Optional<String> resolve(R request);

    /**
     * Returns a resolver that falls back to {@code next} when this one yields nothing.
     *
     * @param next the resolver to try second
     * @return a composed resolver
     */
    default KeyResolver<R> or(KeyResolver<R> next) {
        return request -> {
            Optional<String> first = resolve(request);
            return first.isPresent() ? first : next.resolve(request);
        };
    }
}
