package com.msa.delivery_service.service;

import com.msa.core_common.error.exception.CustomException;
import com.msa.delivery_service.client.hub.dto.HubRouteResponse;
import com.msa.delivery_service.entity.Delivery;
import com.msa.delivery_service.entity.DeliveryRouteHistory;
import com.msa.delivery_service.enums.DeliveryErrorCode;
import com.msa.delivery_service.enums.DeliveryRouteType;
import com.msa.delivery_service.client.user.dto.DeliveryManagerResponse;
import com.msa.delivery_service.client.user.dto.HubManagerResponse;
import com.msa.delivery_service.repository.DeliveryRepository;
import com.msa.delivery_service.repository.DeliveryRouteHistoryRepository;
import com.msa.delivery_service.message.DeadlineRequestedEvent;
import com.msa.delivery_service.message.RedisStreamEventPublisher;
import com.msa.delivery_service.dto.DeliveryRequest;
import com.msa.delivery_service.dto.DeliveryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
    private final DeliveryAssignmentCountService deliveryAssignmentCountService;
    // 배송 생성 단계별 처리 시간 계측
    private final DeliveryPerformanceMetrics performanceMetrics;

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
        // 배송 생성 트랜잭션 전체 시간 계측
        long transactionStartNanos = performanceMetrics.start();

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
            // 배송 저장 처리 시간 계측
            Delivery savedDelivery = performanceMetrics.recordCreateStage(
                    "delivery_save",
                    () -> deliveryRepository.save(delivery)
            );
            log.info("event=DELIVERY_ENTITY_SAVED orderId={} deliveryId={} departureHubId={} destinationHubId={}",
                    request.getOrderId(),
                    savedDelivery.getDeliveryId(),
                    departureHubId,
                    destinationHubId
            );

            List<DeliveryRouteHistory> routeHistories = HubRouteResponse.toDeliveryRouteHistories(
                    savedDelivery,
                    companyDeliveryManager.getDeliveryManagerId(),
                    hubRoutes,
                    hubDeliveryManagerIds
            );
            // 배송 경로 저장 처리 시간 계측
            performanceMetrics.recordCreateStage(
                    "route_history_save_all",
                    () -> deliveryRouteHistoryRepository.saveAll(routeHistories)
            );
            log.info("event=DELIVERY_ROUTE_HISTORY_SAVED orderId={} deliveryId={} routeHistoryCount={}",
                    request.getOrderId(),
                    savedDelivery.getDeliveryId(),
                    routeHistories.size()
            );
            // 업체 배송 담당자 집계 증가 처리 시간 계측
            performanceMetrics.recordCreateStage(
                    "assignment_count_company_increase",
                    () -> deliveryAssignmentCountService.increaseCompanyAssignment(
                            savedDelivery.getCompanyDeliveryManagerId()
                    )
            );
            // 허브 배송 담당자 집계 증가 처리 시간 계측
            performanceMetrics.recordCreateStage(
                    "assignment_count_hub_increase",
                    () -> deliveryAssignmentCountService.increaseHubAssignments(
                            routeHistories.stream()
                                    .filter(routeHistory -> routeHistory.getRouteType() == DeliveryRouteType.HUB_TO_HUB)
                                    .map(DeliveryRouteHistory::getDeliveryManagerId)
                                    .toList()
                    )
            );

            // 커밋이 완료되면 콜백으로 이벤트 발행
            // 콜백 등록 처리 시간 계측
            performanceMetrics.recordCreateStage(
                    "deadline_event_register",
                    () -> redisStreamEventPublisher.publishAfterCommit(
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
                    )
            );
            log.info("event=AI_DEADLINE_REQUEST_REGISTERED orderId={} deliveryId={} stream={}",
                    request.getOrderId(),
                    savedDelivery.getDeliveryId(),
                    RedisStreamEventPublisher.DEADLINE_REQUESTED_STREAM
            );

            DeliveryResponse response = DeliveryResponse.from(savedDelivery);
            // Outbox 저장 처리 시간 계측
            performanceMetrics.recordCreateStage(
                    "outbox_enqueue",
                    () -> deliveryOutboxService.enqueue(CREATE_SUCCEED_TOPIC, request.getOrderId().toString(), response)
            );
            log.info("event=DELIVERY_SUCCESS_OUTBOX_ENQUEUED orderId={} deliveryId={} topic={}",
                    request.getOrderId(),
                    savedDelivery.getDeliveryId(),
                    CREATE_SUCCEED_TOPIC
            );
            return response;
        } catch (DataIntegrityViolationException e) {
            log.warn("event=DELIVERY_CREATE_INTEGRITY_FAILED orderId={} reason={}",
                    request.getOrderId(),
                    e.getMostSpecificCause().getMessage()
            );
            throw new CustomException(translateIntegrityException(e));
        } finally {
            // 배송 생성 트랜잭션 전체 시간 기록
            performanceMetrics.recordCreateStage("total_transaction", transactionStartNanos);
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
