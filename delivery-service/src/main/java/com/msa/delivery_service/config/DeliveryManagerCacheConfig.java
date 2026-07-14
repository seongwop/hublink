package com.msa.delivery_service.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

@EnableCaching
@Configuration
public class DeliveryManagerCacheConfig {

    public static final String DELIVERY_MANAGER_CACHE = "deliveryManagersByHub";

    @Bean
    // 배송 담당자 후보 로컬 캐시 설정
    public CacheManager cacheManager(
            @Value("${delivery.manager-cache.ttl-seconds:60}") long ttlSeconds,
            @Value("${delivery.manager-cache.maximum-hubs:32}") long maximumHubs
    ) {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCacheNames(List.of(DELIVERY_MANAGER_CACHE));
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .maximumSize(maximumHubs)
                .recordStats());
        return cacheManager;
    }
}
