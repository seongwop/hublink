package com.msa.delivery_service.client;

import com.msa.core_common.error.exception.CustomException;
import com.msa.delivery_service.client.hub.HubClient;
import com.msa.delivery_service.client.hub.dto.HubResponse;
import com.msa.delivery_service.client.hub.dto.HubRouteResponse;
import com.msa.delivery_service.client.user.UserClient;
import com.msa.delivery_service.client.user.dto.DeliveryManagerResponse;
import com.msa.delivery_service.client.user.dto.HubManagerResponse;
import com.msa.delivery_service.dto.DeliveryRequest;
import com.msa.delivery_service.enums.DeliveryErrorCode;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeliveryExternalService {

    private final HubClient hubClient;
    private final UserClient userClient;

    @CircuitBreaker(name = "hub-service", fallbackMethod = "getHubFallback")
    public HubResponse getHub(UUID hubId) {
        return hubClient.getHub(hubId);
    }

    @CircuitBreaker(name = "hub-service", fallbackMethod = "getHubRoutesFallback")
    public List<HubRouteResponse> getHubRoutes(DeliveryRequest request) {
        return hubClient.getRoutes(
                request.getSupplyCompanyId(),
                request.getReceiverCompanyId()
        );
    }

    @CircuitBreaker(name = "user-service", fallbackMethod = "getHubManagerFallback")
    public HubManagerResponse getHubManager(UUID departureHubId) {
        return userClient.getHubManager(departureHubId);
    }

    @CircuitBreaker(name = "user-service", fallbackMethod = "getDeliveryManagersFallback")
    public List<DeliveryManagerResponse> getDeliveryManagers(List<UUID> hubIds) {
        return userClient.getDeliveryManagers(hubIds);
    }

    private HubResponse getHubFallback(UUID hubId, Throwable e) {
        throw new CustomException(e instanceof FeignException.NotFound
                ? DeliveryErrorCode.HUB_NOT_FOUND
                : DeliveryErrorCode.HUB_SERVICE_UNAVAILABLE);
    }

    private List<HubRouteResponse> getHubRoutesFallback(DeliveryRequest request, Throwable e) {
        throw new CustomException(e instanceof FeignException.NotFound
                ? DeliveryErrorCode.NO_HUB_ROUTE
                : DeliveryErrorCode.HUB_SERVICE_UNAVAILABLE);
    }

    private HubManagerResponse getHubManagerFallback(UUID departureHubId, Throwable e) {
        throw new CustomException(e instanceof FeignException.NotFound
                ? DeliveryErrorCode.NO_HUB_MANAGER
                : DeliveryErrorCode.USER_SERVICE_UNAVAILABLE);
    }

    private List<DeliveryManagerResponse> getDeliveryManagersFallback(List<UUID> hubIds, Throwable e) {
        throw new CustomException(e instanceof FeignException.NotFound
                ? DeliveryErrorCode.NO_DELIVERY_MANAGER
                : DeliveryErrorCode.USER_SERVICE_UNAVAILABLE);
    }
}
