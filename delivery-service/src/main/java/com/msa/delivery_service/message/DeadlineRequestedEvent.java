package com.msa.delivery_service.message;

import com.msa.delivery_service.entity.Delivery;
import com.msa.delivery_service.client.user.dto.DeliveryManagerResponse;
import com.msa.delivery_service.client.user.dto.HubManagerResponse;
import com.msa.delivery_service.dto.DeliveryRequest;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
public class DeadlineRequestedEvent {

    private UUID eventId;
    private UUID deliveryId;
    private UUID orderId;
    private String ordererName;
    private String ordererEmail;
    private LocalDateTime orderedAt;
    private String requestMessage;
    private UUID receiverUserId;
    private String receiverSlackId;
    private List<DeliveryRequest.Product> products;
    private LocalDateTime requestedArrivalAt;
    private String departureHubName;
    private String destinationAddress;
    private String deliveryManagerName;
    private String deliveryManagerEmail;
    private List<RouteInfo> routeInfo;
    private String workStartTime;
    private String workEndTime;

    public static DeadlineRequestedEvent of(
            Delivery delivery,
            DeliveryRequest request,
            HubManagerResponse hubManager,
            DeliveryManagerResponse deliveryManager,
            String departureHubName,
            List<RouteInfo> routeInfo,
            String workStartTime,
            String workEndTime
    ) {
        return DeadlineRequestedEvent.builder()
                .eventId(UUID.randomUUID())
                .deliveryId(delivery.getDeliveryId())
                .orderId(delivery.getOrderId())
                .ordererName(request.getOrdererName())
                .ordererEmail(request.getOrdererEmail())
                .orderedAt(request.getOrderedAt())
                .requestMessage(request.getRequestMessage())
                .receiverUserId(hubManager.getHubManagerId())
                .receiverSlackId(hubManager.getHubManagerSlackId())
                .products(request.getProducts())
                .requestedArrivalAt(request.getRequestedArrivalAt())
                .departureHubName(departureHubName)
                .destinationAddress(delivery.getDeliveryAddress())
                .deliveryManagerName(deliveryManager.getDeliveryManagerName())
                .deliveryManagerEmail(deliveryManager.getDeliveryManagerEmail())
                .routeInfo(routeInfo)
                .workStartTime(workStartTime)
                .workEndTime(workEndTime)
                .build();
    }

    @Getter
    @Builder
    public static class RouteInfo {

        private UUID hubRouteId;
        private Integer sequence;
        private UUID departureHubId;
        private String departureHubName;
        private UUID arrivalHubId;
        private String arrivalHubName;
        private UUID arrivalCompanyId;
        private String arrivalCompanyName;
        private BigDecimal estimatedDistanceKm;
        private Integer estimatedDurationMin;
        private String routeType;
    }
}
