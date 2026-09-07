package com.rrmadon.palang.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Derives the identity a rate limit is enforced against — typically a client IP,
 * an authenticated principal, or an API key.
 *
 * <p>Kept as a distinct type from {@code palang-core}'s {@code KeyResolver}, even
 * though the shape is identical, because the two answer different questions. An
 * idempotency key names one specific operation a client asked for once; a rate
 * limit key names the caller a quota is tracked against. Using one generic
 * {@code KeyResolver<HttpServletRequest>} type for both would make the two
 * indistinguishable to Spring when a single application uses both starters, and
 * fixing that with qualifiers everywhere is more machinery than defining a second,
 * clearly named interface.
 */
@FunctionalInterface
public interface RateLimitKeyResolver {

    /**
     * Resolves the identity a request's rate limit is tracked under.
     *
     * @param request the incoming request
     * @return the key; never null
     */
    String resolve(HttpServletRequest request);
}
