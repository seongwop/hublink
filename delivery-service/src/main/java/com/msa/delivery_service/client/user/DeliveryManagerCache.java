package com.msa.delivery_service.client.user;

import com.msa.delivery_service.client.user.dto.DeliveryManagerResponse;
import com.msa.delivery_service.config.DeliveryManagerCacheConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class DeliveryManagerCache {

    private final UserClient userClient;

    // Hub ID별 담당자 후보 캐시 및 동시 캐시 미스 단일 조회
    @Cacheable(
            cacheNames = DeliveryManagerCacheConfig.DELIVERY_MANAGER_CACHE,
            key = "#hubId",
            sync = true
    )
    public List<DeliveryManagerResponse> getDeliveryManagers(UUID hubId) {
        return List.copyOf(userClient.getDeliveryManagers(List.of(hubId)));
    }
}
