package com.rrmadon.palang.ratelimit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.RedisCodec;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Swaps in the shared Redis backend when {@code palang.rate-limit.store=redis}.
 *
 * <p>Uses Bucket4j's own Lettuce integration rather than the connection Spring Boot
 * builds for {@code spring-boot-starter-data-redis}: Bucket4j's Redis extension
 * talks to Lettuce directly through its own {@link RedisClient}, and mixing that
 * with Spring's connection factory is more integration surface than this module
 * needs for one bucket-check-per-request. The two connections point at the same
 * Redis and share nothing else, so running both costs one extra TCP connection,
 * not a second Redis.
 */
@AutoConfiguration(after = RedisAutoConfiguration.class, before = RateLimitAutoConfiguration.class)
@ConditionalOnClass({RedisClient.class, Bucket4jLettuce.class})
@ConditionalOnProperty(prefix = "palang.rate-limit", name = "store", havingValue = "redis")
@EnableConfigurationProperties({RateLimitProperties.class, RedisProperties.class})
public class RedisRateLimitAutoConfiguration {

    /**
     * A codec matching keys and values to plain strings, which is all a bucket
     * needs — Bucket4j serialises the bucket's own binary state as the value
     * regardless of what value type the codec declares.
     */
    static final RedisCodec<String, byte[]> CODEC = new StringByteArrayCodec();

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    RedisClient palangRateLimitRedisClient(RedisProperties redisProperties) {
        String uri = "redis://" + redisProperties.getHost() + ":" + redisProperties.getPort();
        return RedisClient.create(uri);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    StatefulRedisConnection<String, byte[]> palangRateLimitRedisConnection(RedisClient client) {
        return client.connect(CODEC);
    }

    @Bean
    @ConditionalOnMissingBean(ProxyManager.class)
    ProxyManager<String> palangRateLimitProxyManager(StatefulRedisConnection<String, byte[]> connection) {
        return Bucket4jLettuce.casBasedBuilder(connection)
                .expirationAfterWrite(ExpirationAfterWriteStrategy
                        .basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(10)))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(RateLimiterSource.class)
    RateLimiterSource redisRateLimiterSource(ProxyManager<String> proxyManager, RateLimitProperties properties) {
        long capacity = properties.getCapacity();
        Duration refillPeriod = properties.getRefillPeriod();
        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(limit -> limit.capacity(capacity).refillGreedy(capacity, refillPeriod))
                .build();
        return key -> proxyManager.getProxy(key, () -> configuration);
    }

    /** Keys and values as UTF-8 strings and raw bytes respectively. */
    private static final class StringByteArrayCodec implements RedisCodec<String, byte[]> {
        @Override
        public String decodeKey(ByteBuffer bytes) {
            return StandardCharsets.UTF_8.decode(bytes).toString();
        }

        @Override
        public byte[] decodeValue(ByteBuffer bytes) {
            byte[] array = new byte[bytes.remaining()];
            bytes.get(array);
            return array;
        }

        @Override
        public ByteBuffer encodeKey(String key) {
            return ByteBuffer.wrap(key.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public ByteBuffer encodeValue(byte[] value) {
            return ByteBuffer.wrap(value);
        }
    }
}
