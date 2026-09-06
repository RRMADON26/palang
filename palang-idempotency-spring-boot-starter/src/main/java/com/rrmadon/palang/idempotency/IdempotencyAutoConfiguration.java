package com.rrmadon.palang.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rrmadon.palang.core.IdempotencyStore;
import com.rrmadon.palang.core.KeyResolver;
import com.rrmadon.palang.core.store.InMemoryIdempotencyStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;

import java.time.Clock;

/**
 * Wires duplicate request suppression into a servlet application.
 *
 * <p>Every bean is conditional, so an application can replace the store, the key
 * resolver, or the clock without switching the rest of the feature off.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "palang.idempotency", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyAutoConfiguration {

    /**
     * Order placing the filter after Spring Security's chain, so an authenticated
     * principal is available to a custom key resolver, but before any controller.
     */
    public static final int FILTER_ORDER = 0;

    /**
     * Supplies the clock used for stored response timestamps when the application
     * does not define one.
     *
     * @return the system UTC clock
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock palangClock() {
        return Clock.systemUTC();
    }

    /**
     * Per-instance store, used unless Redis is selected or a store bean is supplied.
     *
     * @param properties behaviour configuration
     * @param clock      source of time
     * @return an in-memory store
     */
    @Bean
    @ConditionalOnMissingBean(IdempotencyStore.class)
    @ConditionalOnProperty(prefix = "palang.idempotency", name = "store",
            havingValue = "memory", matchIfMissing = true)
    public IdempotencyStore inMemoryIdempotencyStore(IdempotencyProperties properties, Clock clock) {
        return new InMemoryIdempotencyStore(clock, properties.getInFlightTtl());
    }

    /**
     * Reads the key from the configured header.
     *
     * @param properties behaviour configuration
     * @return a header-based key resolver
     */
    @Bean
    @ConditionalOnMissingBean
    public KeyResolver<HttpServletRequest> idempotencyKeyResolver(IdempotencyProperties properties) {
        return new HeaderKeyResolver(properties.getHeaderName());
    }

    /**
     * Registers the filter.
     *
     * @param store        where keys live
     * @param keyResolver  derives the key
     * @param properties   behaviour configuration
     * @param objectMapper renders problem responses
     * @param clock        source of time
     * @return the filter registration
     */
    @Bean
    @Order(FILTER_ORDER)
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(
            IdempotencyStore store,
            KeyResolver<HttpServletRequest> keyResolver,
            IdempotencyProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {

        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new IdempotencyFilter(store, keyResolver, properties, objectMapper, clock));
        registration.setOrder(FILTER_ORDER);
        registration.addUrlPatterns("/*");
        registration.setName("palangIdempotencyFilter");
        return registration;
    }
}
