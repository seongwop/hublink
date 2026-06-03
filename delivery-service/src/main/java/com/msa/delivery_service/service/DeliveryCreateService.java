package com.msa.delivery_service.service;

import com.msa.core_common.error.exception.CustomException;
import com.msa.delivery_service.client.hub.dto.HubRouteResponse;
import com.msa.delivery_service.entity.Delivery;
import com.msa.delivery_service.entity.DeliveryRouteHistory;
import com.msa.delivery_service.enums.DeliveryErrorCode;
import com.msa.delivery_service.client.user.dto.DeliveryManagerResponse;
import com.msa.delivery_service.client.user.dto.HubManagerResponse;
import com.msa.delivery_service.repository.DeliveryRepository;
import com.msa.delivery_service.repository.DeliveryRouteHistoryRepository;
import com.msa.delivery_service.message.DeadlineRequestedEvent;
import com.msa.delivery_service.message.RedisStreamEventPublisher;
import com.msa.delivery_service.dto.DeliveryRequest;
import com.msa.delivery_service.dto.DeliveryResponse;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeliveryCreateService {
    private static final String UK_ACTIVE_ORDER_ID =
            "uk_p_deliveries_active_order_id";
    private static final String UK_ACTIVE_COMPANY_DELIVERY_MANAGER =
            "uk_p_deliveries_active_company_delivery_manager";
    private static final String UK_ACTIVE_HUB_DELIVERY_MANAGER =
            "uk_p_delivery_route_histories_active_delivery_manager";
    private static final String CREATE_SUCCEED_TOPIC = "delivery.create.succeed";

    /*
        외부 서비스 호출과 DB 커넥션을 분리하기 위해 트랜잭션을 외부로 분리
        Self-Invocation 방지를 위해 따로 클래스 작성
    */
    private final DeliveryRepository deliveryRepository;
    private final DeliveryRouteHistoryRepository deliveryRouteHistoryRepository;
    private final RedisStreamEventPublisher redisStreamEventPublisher;
    private final DeliveryOutboxService deliveryOutboxService;

    @Transactional
    public DeliveryResponse createDelivery(
            DeliveryRequest request,
            HubManagerResponse hubManager,
            DeliveryManagerResponse companyDeliveryManager,
            List<HubRouteResponse> hubRoutes,
            Map<UUID, UUID> hubDeliveryManagerIds,
            String workStartTime,
            String workEndTime
    ) {
        UUID departureHubId = hubRoutes.get(0).getDepartureHubId();
        UUID destinationHubId = getDestinationHubId(hubRoutes);

        try {
            Delivery delivery = Delivery.create(
                    request.getOrderId(),
                    departureHubId,
                    destinationHubId,
                    request.getReceiverCompanyId(),
                    companyDeliveryManager.getDeliveryManagerId(),
                    request.getDeliveryAddress(),
                    request.getReceiverName(),
                    hubManager.getHubManagerSlackId()
            );
            delivery.updateEstimatedArrival(calculateEstimatedArrivalAt(hubRoutes));
            Delivery savedDelivery = deliveryRepository.saveAndFlush(delivery);

            List<DeliveryRouteHistory> routeHistories = HubRouteResponse.toDeliveryRouteHistories(
                    savedDelivery,
                    companyDeliveryManager.getDeliveryManagerId(),
                    hubRoutes,
                    hubDeliveryManagerIds
            );
            deliveryRouteHistoryRepository.saveAllAndFlush(routeHistories);

            // 커밋이 완료되면 콜백으로 이벤트 발행
            redisStreamEventPublisher.publishAfterCommit(
                    RedisStreamEventPublisher.DEADLINE_REQUESTED_STREAM,
                    DeadlineRequestedEvent.of(
                            savedDelivery,
                            request,
                            hubManager.getHubManagerId(),
                            hubManager.getHubManagerSlackId(),
                            companyDeliveryManager.getDeliveryManagerName(),
                            companyDeliveryManager.getDeliveryManagerEmail(),
                            hubRoutes.get(0).getDepartureHubName(),
                            toDeadlineRouteInfo(hubRoutes),
                            workStartTime,
                            workEndTime
                    )
            );

            DeliveryResponse response = DeliveryResponse.from(savedDelivery);
            deliveryOutboxService.enqueue(CREATE_SUCCEED_TOPIC, request.getOrderId().toString(), response);
            return response;
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(translateIntegrityException(e));
        }
    }

    private LocalDateTime calculateEstimatedArrivalAt(List<HubRouteResponse> hubRoutes) {
        long totalEstimatedDurationMinutes = hubRoutes.stream()
                .map(HubRouteResponse::getEstimatedDurationMin)
                .filter(duration -> duration != null)
                .mapToLong(Integer::longValue)
                .sum();
        return LocalDateTime.now().plusMinutes(totalEstimatedDurationMinutes);
    }

    private List<DeadlineRequestedEvent.RouteInfo> toDeadlineRouteInfo(List<HubRouteResponse> hubRoutes) {
        return hubRoutes.stream()
                .map(hubRoute -> DeadlineRequestedEvent.RouteInfo.builder()
                        .hubRouteId(hubRoute.getHubRouteId())
                        .sequence(hubRoute.getSequence())
                        .departureHubId(hubRoute.getDepartureHubId())
                        .departureHubName(hubRoute.getDepartureHubName())
                        .arrivalHubId(hubRoute.getArrivalHubId())
                        .arrivalHubName(hubRoute.getArrivalHubName())
                        .arrivalCompanyId(hubRoute.getArrivalCompanyId())
                        .arrivalCompanyName(hubRoute.getArrivalCompanyName())
                        .estimatedDistanceKm(hubRoute.getEstimatedDistanceKm())
                        .estimatedDurationMin(hubRoute.getEstimatedDurationMin())
                        .routeType(hubRoute.getRouteType())
                        .build())
                .toList();
    }

    private UUID getDestinationHubId(List<HubRouteResponse> hubRoutes) {
        return hubRoutes.get(hubRoutes.size() - 1).getDepartureHubId();
    }

    // 현재 유니크 제약이 총 3곳에 적용되어 있으므로 구분하기 위한 메서드
    // Delivery 중복 제약
    // 업체 배송 담당 기사 제약
    // 허브 배송 담당 기사 제약
    private DeliveryErrorCode translateIntegrityException(DataIntegrityViolationException e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException constraintViolationException) {
                String constraintName = constraintViolationException.getConstraintName();
                if (UK_ACTIVE_ORDER_ID.equals(constraintName)) {
                    return DeliveryErrorCode.DUPLICATE_ORDER_DELIVERY;
                }
                if (UK_ACTIVE_COMPANY_DELIVERY_MANAGER.equals(constraintName)
                        || UK_ACTIVE_HUB_DELIVERY_MANAGER.equals(constraintName)) {
                    return DeliveryErrorCode.DELIVERY_ASSIGNMENT_CONFLICT;
                }
                break;
            }
            cause = cause.getCause();
        }

        return DeliveryErrorCode.DUPLICATE_ORDER_DELIVERY;
    }
}
