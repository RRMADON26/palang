package com.rrmadon.palang.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Keys on {@link HttpServletRequest#getRemoteAddr()}.
 *
 * <p>Behind a reverse proxy or load balancer, this resolves to the proxy's address
 * for every request, not the client's, which collapses every caller onto one
 * bucket. Applications deployed behind a proxy should supply a
 * {@link RateLimitKeyResolver} bean that reads {@code X-Forwarded-For} instead —
 * but only after configuring the proxy to strip any such header a client sent
 * directly, since otherwise a client can forge the header and evade the limit
 * entirely by presenting a different claimed address on every request.
 */
public final class RemoteAddressKeyResolver implements RateLimitKeyResolver {

    @Override
    public String resolve(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
