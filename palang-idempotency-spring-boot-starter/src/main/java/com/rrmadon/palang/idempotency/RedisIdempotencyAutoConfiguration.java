package com.rrmadon.palang.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rrmadon.palang.core.IdempotencyStore;
import com.rrmadon.palang.idempotency.store.RedisIdempotencyStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;

/**
 * Swaps in the shared Redis store when {@code palang.idempotency.store=redis}.
 *
 * <p>Kept separate from the main auto-configuration so an application without
 * Spring Data Redis on the classpath never sees these beans attempted.
 */
@AutoConfiguration(after = RedisAutoConfiguration.class, before = IdempotencyAutoConfiguration.class)
@ConditionalOnClass(StringRedisTemplate.class)
@ConditionalOnProperty(prefix = "palang.idempotency", name = "store", havingValue = "redis")
@EnableConfigurationProperties(IdempotencyProperties.class)
public class RedisIdempotencyAutoConfiguration {

    /**
     * Builds the Redis-backed store.
     *
     * @param redis        template addressing the shared Redis
     * @param objectMapper used to serialise stored responses
     * @param properties   behaviour configuration
     * @param clock        source of time
     * @return a store shared across every replica
     */
    @Bean
    @ConditionalOnMissingBean(IdempotencyStore.class)
    public IdempotencyStore redisIdempotencyStore(StringRedisTemplate redis,
                                                  ObjectMapper objectMapper,
                                                  IdempotencyProperties properties,
                                                  Clock clock) {
        return new RedisIdempotencyStore(redis, objectMapper, clock, properties.getInFlightTtl());
    }

    /**
     * Supplies a clock when the main auto-configuration has not yet contributed one.
     *
     * @return the system UTC clock
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock palangClock() {
        return Clock.systemUTC();
    }
}
