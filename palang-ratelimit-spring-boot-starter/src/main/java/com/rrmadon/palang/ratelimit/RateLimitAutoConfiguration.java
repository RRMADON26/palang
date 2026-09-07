package com.rrmadon.palang.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;

import java.time.Clock;
import java.time.Duration;

/**
 * Wires request-rate limiting into a servlet application.
 *
 * <p>{@link RemoteAddressKeyResolver} and the in-memory {@link RateLimiterSource}
 * are the defaults; both are {@code @ConditionalOnMissingBean}, so an application
 * behind a proxy or wanting Redis can replace either without switching the
 * feature off.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "palang.rate-limit", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitAutoConfiguration {

    /**
     * Supplies the clock used to render {@code RateLimit-Reset} when the
     * application does not define one.
     *
     * @return the system UTC clock
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock palangRateLimitClock() {
        return Clock.systemUTC();
    }

    /**
     * Keys on the remote address unless the application supplies its own resolver —
     * for instance one reading a header set by a trusted reverse proxy.
     *
     * @return the default resolver
     */
    @Bean
    @ConditionalOnMissingBean(RateLimitKeyResolver.class)
    public RateLimitKeyResolver rateLimitKeyResolver() {
        return new RemoteAddressKeyResolver();
    }

    /**
     * Per-instance bucket cache, used unless Redis is selected or a source bean is
     * supplied. Bounded with {@link RateLimitProperties#getMemoryIdleEviction()} so
     * an ever-growing set of distinct keys — client IPs over the life of a process —
     * does not grow the cache without limit.
     *
     * @param properties behaviour configuration
     * @return an in-memory bucket source
     */
    @Bean
    @ConditionalOnMissingBean(RateLimiterSource.class)
    @ConditionalOnProperty(prefix = "palang.rate-limit", name = "store",
            havingValue = "memory", matchIfMissing = true)
    public RateLimiterSource inMemoryRateLimiterSource(RateLimitProperties properties) {
        long capacity = properties.getCapacity();
        Duration refillPeriod = properties.getRefillPeriod();
        Cache<String, Bucket> cache = Caffeine.newBuilder()
                .expireAfterAccess(properties.getMemoryIdleEviction())
                .build();
        return key -> cache.get(key, k -> Bucket.builder()
                .addLimit(limit -> limit.capacity(capacity).refillGreedy(capacity, refillPeriod))
                .build());
    }

    /**
     * Registers the filter.
     *
     * @param source       supplies the bucket for a resolved key
     * @param keyResolver  derives the identity a limit is enforced against
     * @param properties   behaviour configuration
     * @param objectMapper renders problem responses
     * @param clock        source of time for {@code RateLimit-Reset}
     * @return the filter registration
     */
    @Bean
    @Order(RateLimitFilter.FILTER_ORDER)
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            RateLimiterSource source,
            RateLimitKeyResolver keyResolver,
            RateLimitProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {

        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RateLimitFilter(source, keyResolver, properties, objectMapper, clock));
        registration.setOrder(RateLimitFilter.FILTER_ORDER);
        registration.addUrlPatterns("/*");
        registration.setName("palangRateLimitFilter");
        return registration;
    }
}
