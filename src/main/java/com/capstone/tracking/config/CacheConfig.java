package com.capstone.tracking.config;

import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;

import java.time.Duration;

/**
 * Read caches for hot, rarely-changing lists. The backend is chosen by {@code spring.cache.type}: {@code none}
 * (default, e.g. Railway without Redis), {@code redis}, or {@code simple} (in-memory). Cached values are
 * Serializable DTOs, never entities.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * transactionAware: puts/evicts run after the surrounding transaction commits, so a change cannot be evicted and
     * re-cached with the old value before it is visible.
     */
    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheCustomizer() {
        return builder -> builder
                .cacheDefaults(RedisCacheConfiguration.defaultCacheConfig()
                        .entryTtl(Duration.ofMinutes(10))
                        .disableCachingNullValues()
                        .prefixCacheNameWith("capstone::"))
                .transactionAware();
    }
}
