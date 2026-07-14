package com.msa.delivery_service.client;

import com.msa.delivery_service.client.hub.HubClient;
import com.msa.delivery_service.client.user.DeliveryManagerCache;
import com.msa.delivery_service.client.user.UserClient;
import com.msa.delivery_service.client.user.dto.DeliveryManagerResponse;
import com.msa.delivery_service.config.DeliveryManagerCacheConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(classes = {
        DeliveryManagerCacheConfig.class,
        DeliveryExternalServiceCacheTest.TestConfig.class
})
@TestPropertySource(properties = {
        "delivery.manager-cache.ttl-seconds=60",
        "delivery.manager-cache.maximum-hubs=10"
})
class DeliveryExternalServiceCacheTest {

    @Autowired
    private DeliveryExternalService deliveryExternalService;

    @Autowired
    private DeliveryManagerCache deliveryManagerCache;

    @Autowired
    private UserClient userClient;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        reset(userClient);
        cacheManager.getCache(DeliveryManagerCacheConfig.DELIVERY_MANAGER_CACHE).clear();
        when(userClient.getDeliveryManagers(anyList())).thenReturn(List.of());
    }

    @Test
    @DisplayName("담당자 조회: Hub 순서가 달라도 캐시 재사용")
    void getDeliveryManagersUsesSameCacheForReorderedHubIds() {
        UUID seoulHubId = UUID.randomUUID();
        UUID busanHubId = UUID.randomUUID();

        deliveryExternalService.getDeliveryManagers(List.of(seoulHubId, busanHubId));
        deliveryExternalService.getDeliveryManagers(List.of(busanHubId, seoulHubId));

        verify(userClient, times(2)).getDeliveryManagers(anyList());
        verify(userClient).getDeliveryManagers(List.of(seoulHubId));
        verify(userClient).getDeliveryManagers(List.of(busanHubId));
    }

    @Test
    @DisplayName("담당자 조회: Hub 조합별 캐시 분리")
    void getDeliveryManagersUsesDifferentCacheForDifferentHubIds() {
        UUID seoulHubId = UUID.randomUUID();
        UUID busanHubId = UUID.randomUUID();
        UUID incheonHubId = UUID.randomUUID();

        deliveryExternalService.getDeliveryManagers(List.of(seoulHubId, busanHubId));
        deliveryExternalService.getDeliveryManagers(List.of(seoulHubId, incheonHubId));

        verify(userClient, times(3)).getDeliveryManagers(anyList());
        verify(userClient).getDeliveryManagers(List.of(seoulHubId));
        verify(userClient).getDeliveryManagers(List.of(busanHubId));
        verify(userClient).getDeliveryManagers(List.of(incheonHubId));
    }

    @Test
    @DisplayName("담당자 조회: 동일 Hub 동시 캐시 미스 단일 호출")
    void getDeliveryManagersLoadsSameCacheKeyOnceConcurrently() throws Exception {
        UUID seoulHubId = UUID.randomUUID();
        int concurrency = 10;
        CyclicBarrier startBarrier = new CyclicBarrier(concurrency);
        CountDownLatch userCallStarted = new CountDownLatch(1);
        CountDownLatch releaseUserCall = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);

        when(userClient.getDeliveryManagers(anyList())).thenAnswer(invocation -> {
            userCallStarted.countDown();
            if (!releaseUserCall.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("user-service mock release timeout");
            }
            return List.<DeliveryManagerResponse>of();
        });

        try {
            List<Future<List<DeliveryManagerResponse>>> futures = IntStream.range(0, concurrency)
                    .mapToObj(index -> executor.submit(() -> {
                        startBarrier.await();
                        return deliveryManagerCache.getDeliveryManagers(seoulHubId);
                    }))
                    .toList();

            assertThat(userCallStarted.await(1, TimeUnit.SECONDS)).isTrue();
            releaseUserCall.countDown();

            for (Future<List<DeliveryManagerResponse>> future : futures) {
                assertThat(future.get(2, TimeUnit.SECONDS)).isEmpty();
            }
        } finally {
            releaseUserCall.countDown();
            executor.shutdownNow();
        }

        verify(userClient, times(1)).getDeliveryManagers(anyList());
    }

    @Configuration
    static class TestConfig {

        @Bean
        HubClient hubClient() {
            return mock(HubClient.class);
        }

        @Bean
        UserClient userClient() {
            return mock(UserClient.class);
        }

        @Bean
        DeliveryManagerCache deliveryManagerCache(UserClient userClient) {
            return new DeliveryManagerCache(userClient);
        }

        @Bean
        DeliveryExternalService deliveryExternalService(
                HubClient hubClient,
                UserClient userClient,
                DeliveryManagerCache deliveryManagerCache
        ) {
            return new DeliveryExternalService(hubClient, userClient, deliveryManagerCache);
        }
    }
}
