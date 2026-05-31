package com.msa.delivery_service.service;

import com.msa.core_common.error.exception.CustomException;
import com.msa.core_common.response.paging.PageRes;
import com.msa.delivery_service.client.DeliveryExternalService;
import com.msa.delivery_service.client.hub.dto.HubRouteResponse;
import com.msa.delivery_service.entity.Delivery;
import com.msa.delivery_service.entity.DeliveryRouteHistory;
import com.msa.delivery_service.enums.DeliveryErrorCode;
import com.msa.delivery_service.enums.DeliveryRouteStatus;
import com.msa.delivery_service.enums.DeliveryStatus;
import com.msa.delivery_service.client.user.dto.DeliveryManagerResponse;
import com.msa.delivery_service.client.user.dto.HubManagerResponse;
import com.msa.delivery_service.repository.DeliveryRepository;
import com.msa.delivery_service.repository.DeliveryRouteHistoryRepository;
import com.msa.delivery_service.message.DeadlineGeneratedEvent;
import com.msa.delivery_service.dto.DeliveryDetailResponse;
import com.msa.delivery_service.dto.DeliveryRequest;
import com.msa.delivery_service.dto.DeliveryResponse;
import com.msa.delivery_service.dto.DeliveryRouteHistoryResponse;
import com.msa.delivery_service.dto.DeliveryRouteStatusUpdateRequest;
import com.msa.delivery_service.dto.DeliveryStatusUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeliveryService {

    // 배송 담당자 근무 시간 고정
    private static final String WORK_START_TIME = "09:00";
    private static final String WORK_END_TIME = "18:00";

    // 배송 담당자 타입 구분
    private static final String COMPANY_DELIVERY_MANAGER_TYPE = "COMPANY_DELIVERY";
    private static final String HUB_DELIVERY_MANAGER_TYPE = "HUB_DELIVERY";

    // USER 권한
    private static final String MASTER = "MASTER";
    private static final String HUB_MANAGER = "HUB_MANAGER";
    private static final String DELIVERY_MANAGER = "DELIVERY_MANAGER";
    private static final String SUPPLIER_MANAGER = "SUPPLIER_MANAGER";

    private final DeliveryRepository deliveryRepository;
    private final DeliveryRouteHistoryRepository deliveryRouteHistoryRepository;
    private final DeliveryExternalService deliveryExternalService;
    private final DeliveryCreateService deliveryCreateService;
    private final DeliveryAssignmentLockService deliveryAssignmentLockService;

    @Transactional(readOnly = true)
    public PageRes<DeliveryResponse> getDeliveries(String role, Pageable pageable) {
        // MASTER: 전체 배송 목록 조회 가능
        // 그 외 권한: 접근 불가
        Page<DeliveryResponse> deliveries = switch (role) {
            case MASTER -> deliveryRepository.findAll(pageable)
                    .map(DeliveryResponse::from);
            default -> throw new CustomException(DeliveryErrorCode.ACCESS_DENIED);
        };

        return new PageRes<>(deliveries);
    }

    @Transactional(readOnly = true)
    public PageRes<DeliveryResponse> getMyDeliveries(UUID userId, String role, Pageable pageable) {
        // DELIVERY_MANAGER: 본인에게 배정된 배송 목록 조회 가능
        Page<DeliveryResponse> deliveries = switch (role) {
            case DELIVERY_MANAGER -> deliveryRepository.findAllByCompanyDeliveryManagerId(userId, pageable)
                    .map(DeliveryResponse::from);
            default -> throw new CustomException(DeliveryErrorCode.ACCESS_DENIED);
        };

        return new PageRes<>(deliveries);
    }

    @Transactional(readOnly = true)
    public DeliveryDetailResponse getDelivery(UUID userId, String role, UUID deliveryId) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new CustomException(DeliveryErrorCode.DELIVERY_NOT_FOUND));
        List<DeliveryRouteHistory> routeHistories = deliveryRouteHistoryRepository
                .findByDeliveryDeliveryIdOrderBySequenceAsc(deliveryId);

        // MASTER, HUB_MANAGER, SUPPLIER_MANAGER: 배송 상세 조회 가능
        // DELIVERY_MANAGER: 본인에게 배정된 배송만 조회 가능 (경로를 포함하기 때문에 허브 배송 담당자도 조회 가능)
        return switch (role) {
            case MASTER, HUB_MANAGER, SUPPLIER_MANAGER -> DeliveryDetailResponse.of(delivery, routeHistories);
            case DELIVERY_MANAGER -> {
                boolean assignedDelivery = userId.equals(delivery.getCompanyDeliveryManagerId())
                        || deliveryRouteHistoryRepository.existsByDeliveryDeliveryIdAndDeliveryManagerId(
                        delivery.getDeliveryId(),
                        userId
                );
                if (!assignedDelivery) throw new CustomException(DeliveryErrorCode.ACCESS_DENIED);

                yield DeliveryDetailResponse.of(delivery, routeHistories);
            }
            default -> throw new CustomException(DeliveryErrorCode.ACCESS_DENIED);
        };
    }

    @Transactional(readOnly = true)
    public DeliveryResponse getDeliveryByOrderId(String role, UUID orderId) {
        Delivery delivery = deliveryRepository.findByOrderId(orderId)
                .orElseThrow(() -> new CustomException(DeliveryErrorCode.DELIVERY_NOT_FOUND));

        // MASTER, HUB_MANAGER, SUPPLIER_MANAGER: 주문 기준 배송 조회 가능
        return switch (role) {
            case MASTER, HUB_MANAGER, SUPPLIER_MANAGER -> DeliveryResponse.from(delivery);
            default -> throw new CustomException(DeliveryErrorCode.ACCESS_DENIED);
        };
    }

    @Transactional(readOnly = true)
    public Optional<DeliveryResponse> findDeliveryByOrderId(UUID orderId) {
        return deliveryRepository.findByOrderId(orderId)
                .map(DeliveryResponse::from);
    }

    @Transactional
    public DeliveryResponse updateDeliveryStatus(
            UUID userId,
            String role,
            UUID deliveryId,
            DeliveryStatusUpdateRequest request
    ) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new CustomException(DeliveryErrorCode.DELIVERY_NOT_FOUND));

        // MASTER, HUB_MANAGER: 대표 배송 상태 변경 가능
        // DELIVERY_MANAGER: 업체 배송 담당자인 경우만 변경 가능
        switch (role) {
            case MASTER, HUB_MANAGER -> {}
            case DELIVERY_MANAGER -> {
                if (!userId.equals(delivery.getCompanyDeliveryManagerId())) {
                    throw new CustomException(DeliveryErrorCode.ACCESS_DENIED);
                }
            }
            default -> throw new CustomException(DeliveryErrorCode.ACCESS_DENIED);
        }

        if (request.getStatus() == DeliveryStatus.DELIVERED) {
            delivery.complete();
        } else {
            delivery.updateStatus(request.getStatus());
        }

        deliveryRepository.flush();

        return DeliveryResponse.from(delivery);
    }

    @Transactional
    public DeliveryRouteHistoryResponse updateRouteHistoryStatus(
            UUID userId,
            String role,
            UUID routeHistoryId,
            DeliveryRouteStatusUpdateRequest request
    ) {
        DeliveryRouteHistory routeHistory = deliveryRouteHistoryRepository.findById(routeHistoryId)
                .orElseThrow(() -> new CustomException(DeliveryErrorCode.DELIVERY_ROUTE_HISTORY_NOT_FOUND));

        // MASTER, HUB_MANAGER: 경로 상태 변경 가능
        // DELIVERY_MANAGER: 본인에게 배정된 경로만 변경 가능
        switch (role) {
            case MASTER, HUB_MANAGER -> {}
            case DELIVERY_MANAGER -> {
                if (!userId.equals(routeHistory.getDeliveryManagerId())) {
                    throw new CustomException(DeliveryErrorCode.ACCESS_DENIED);
                }
            }
            default -> throw new CustomException(DeliveryErrorCode.ACCESS_DENIED);
        }

        if (request.getStatus() == DeliveryRouteStatus.COMPLETED) {
            routeHistory.complete(request.getActualDistanceKm(), request.getActualDurationMin());
        } else {
            routeHistory.updateStatus(request.getStatus());
        }

        if (request.getStatusMessage() != null) {
            routeHistory.updateStatusMessage(request.getStatusMessage());
        }

        deliveryRouteHistoryRepository.flush();

        return DeliveryRouteHistoryResponse.from(routeHistory);
    }

    /*
        배송 생성 이벤트 수신 시 내부 호출 메서드
    */

    public DeliveryResponse createDelivery(DeliveryRequest request) {
        if (deliveryRepository.existsByOrderId(request.getOrderId())) {
            throw new CustomException(DeliveryErrorCode.DUPLICATE_ORDER_DELIVERY);
        }

        List<HubRouteResponse> hubRoutes = getHubRoutes(request);
        HubManagerResponse hubManager = getHubManager(getDepartureHubId(hubRoutes));
        List<DeliveryManagerResponse> deliveryManagers = getDeliveryManagers(hubRoutes);
        // 업체 배송 기사 Lock 키 1개 + 허브 배송 기사 Lock 키 N개
        List<String> lockKeys = buildAssignmentLockKeys(hubRoutes);

        // Lock을 전부 잡고 인자로 들어간 function을 수행
        return deliveryAssignmentLockService.executeWithLocks(lockKeys, () -> {
            DeliveryManagerResponse companyDeliveryManager = assignCompanyDeliveryManager(
                    deliveryManagers,
                    getDestinationHubId(hubRoutes)
            );
            Map<UUID, UUID> hubDeliveryManagerIds = assignHubDeliveryManagers(hubRoutes, deliveryManagers);

            return deliveryCreateService.createDelivery(
                    request,
                    hubManager,
                    companyDeliveryManager,
                    hubRoutes,
                    hubDeliveryManagerIds,
                    WORK_START_TIME,
                    WORK_END_TIME
            );
        });
    }

    private List<String> buildAssignmentLockKeys(List<HubRouteResponse> hubRoutes) {
        List<String> lockKeys = new ArrayList<>();

        // 마지막 허브가 업체 배송 허브이므로 따로 Lock 키 생성
        UUID destinationHubId = getDestinationHubId(hubRoutes);
        lockKeys.add("lock:delivery:company:" + destinationHubId);

        // 마지막 경로를 제외하고는 전부 허브-허브 경로
        for (int i = 0; i < hubRoutes.size() - 1; i++) {
            lockKeys.add("lock:delivery:hub:" + hubRoutes.get(i).getDepartureHubId());
        }
        return lockKeys;
    }

    @Transactional
    public void updateFinalDepartureDeadline(DeadlineGeneratedEvent event) {
        Delivery delivery = deliveryRepository.findById(event.getDeliveryId())
                .orElseThrow(() -> new CustomException(DeliveryErrorCode.DELIVERY_NOT_FOUND));
        delivery.updateFinalDepartureDeadline(event.getFinalDepartureDeadline());
        deliveryRepository.flush();
    }

    @Transactional
    public void compensateDeliveryCreation(UUID orderId) {
        deliveryRepository.findByOrderId(orderId)
                .ifPresent(delivery -> {
                    delivery.cancel();
                    delivery.delete("SYSTEM");
                    deliveryRouteHistoryRepository.findByDeliveryDeliveryIdOrderBySequenceAsc(delivery.getDeliveryId())
                        .forEach(routeHistory -> {
                            if (routeHistory.getStatus().canChangeTo(DeliveryRouteStatus.FAILED)) {
                                routeHistory.updateStatus(DeliveryRouteStatus.FAILED);
                            }
                            routeHistory.delete("SYSTEM");
                        });
                    deliveryRouteHistoryRepository.flush();
                    deliveryRepository.flush();
                });
    }

    private HubManagerResponse getHubManager(UUID departureHubId) {
        HubManagerResponse hubManager = deliveryExternalService.getHubManager(departureHubId);
        if (hubManager == null || hubManager.getHubManagerSlackId() == null) {
            throw new CustomException(DeliveryErrorCode.NO_HUB_MANAGER);
        }
        return hubManager;
    }

    // 배송 경로에 필요한 허브들의 배송 담당자 목록 조회
    private List<DeliveryManagerResponse> getDeliveryManagers(List<HubRouteResponse> hubRoutes) {
        Set<UUID> hubIds = new LinkedHashSet<>();
        for (HubRouteResponse hubRoute : hubRoutes) {
            hubIds.add(hubRoute.getDepartureHubId());
        }

        List<DeliveryManagerResponse> deliveryManagers =
                deliveryExternalService.getDeliveryManagers(new ArrayList<>(hubIds));
        if (deliveryManagers == null || deliveryManagers.isEmpty()) {
            throw new CustomException(DeliveryErrorCode.NO_DELIVERY_MANAGER);
        }
        return deliveryManagers;
    }

    // 마지막 업체 배송을 담당할 배송 담당자 배정
    private DeliveryManagerResponse assignCompanyDeliveryManager(
            List<DeliveryManagerResponse> deliveryManagers,
            UUID destinationHubId
    ) {
        List<DeliveryManagerResponse> companyDeliveryManagers = deliveryManagers.stream()
                .filter(deliveryManager -> destinationHubId.equals(deliveryManager.getHubId()))
                .filter(deliveryManager -> COMPANY_DELIVERY_MANAGER_TYPE.equals(deliveryManager.getType()))
                .toList();

        if (companyDeliveryManagers.isEmpty()) {
            throw new CustomException(DeliveryErrorCode.NO_DELIVERY_MANAGER);
        }

        Set<UUID> workingManagerIds = deliveryRepository.findWorkingManagerIds(
                companyDeliveryManagers.stream()
                        .map(DeliveryManagerResponse::getDeliveryManagerId)
                        .toList(),
                List.of(DeliveryStatus.DELIVERED, DeliveryStatus.CANCELLED)
        );

        List<DeliveryManagerResponse> availableManagers = companyDeliveryManagers.stream()
                .filter(deliveryManager -> !workingManagerIds.contains(deliveryManager.getDeliveryManagerId()))
                .toList();

        if (availableManagers.isEmpty()) {
            throw new CustomException(DeliveryErrorCode.NO_DELIVERY_MANAGER);
        }

        return Collections.min(
                availableManagers,
                Comparator.comparing(DeliveryManagerResponse::getDeliverySequence)
        );
    }

    // 허브 간 이동 경로마다 허브 배송 담당자 배정 - <허브 ID, 배송 담당자 ID> 반환
    private Map<UUID, UUID> assignHubDeliveryManagers(
            List<HubRouteResponse> hubRoutes,
            List<DeliveryManagerResponse> deliveryManagers
    ) {
        Map<UUID, UUID> hubDeliveryManagerIds = new HashMap<>();
        // 각 허브에 해당 하는 담당 매니저를 따로 조회해서 발생하던 N+1 문제 방지
        // 처음부터 모든 경로의 허브 배송 담당자들을 전부 미리 조회
        List<DeliveryManagerResponse> hubDeliveryManagers = deliveryManagers.stream()
                .filter(deliveryManager -> HUB_DELIVERY_MANAGER_TYPE.equals(deliveryManager.getType()))
                .toList();
        Set<UUID> workingManagerIds = hubDeliveryManagers.isEmpty()
                ? Set.of()
                : deliveryRouteHistoryRepository.findWorkingManagerIds(
                hubDeliveryManagers.stream()
                        .map(DeliveryManagerResponse::getDeliveryManagerId)
                        .toList(),
                List.of(DeliveryRouteStatus.COMPLETED, DeliveryRouteStatus.SKIPPED, DeliveryRouteStatus.FAILED)
        );

        for (int i = 0; i < hubRoutes.size() - 1; i++) {
            HubRouteResponse hubRoute = hubRoutes.get(i);
            DeliveryManagerResponse hubDeliveryManager = selectHubDeliveryManager(
                    deliveryManagers,
                    hubRoute.getDepartureHubId(),
                    workingManagerIds
            );
            hubDeliveryManagerIds.put(hubRoute.getHubRouteId(), hubDeliveryManager.getDeliveryManagerId());
        }

        return hubDeliveryManagerIds;
    }

    // 특정 출발 허브 구간을 담당할 허브 배송 담당자 선택
    private DeliveryManagerResponse selectHubDeliveryManager(
            List<DeliveryManagerResponse> deliveryManagers,
            UUID departureHubId,
            Set<UUID> workingManagerIds
    ) {
        List<DeliveryManagerResponse> hubDeliveryManagers = deliveryManagers.stream()
                .filter(deliveryManager -> departureHubId.equals(deliveryManager.getHubId()))
                .filter(deliveryManager -> HUB_DELIVERY_MANAGER_TYPE.equals(deliveryManager.getType()))
                .toList();

        if (hubDeliveryManagers.isEmpty()) {
            throw new CustomException(DeliveryErrorCode.NO_DELIVERY_MANAGER);
        }

        List<DeliveryManagerResponse> availableManagers = hubDeliveryManagers.stream()
                .filter(deliveryManager -> !workingManagerIds.contains(deliveryManager.getDeliveryManagerId()))
                .toList();

        if (availableManagers.isEmpty()) {
            throw new CustomException(DeliveryErrorCode.NO_DELIVERY_MANAGER);
        }

        return Collections.min(
                availableManagers,
                Comparator.comparing(DeliveryManagerResponse::getDeliverySequence)
        );
    }

    // 출발 허브와 도착 허브 기준으로 배송 경로 조회
    private List<HubRouteResponse> getHubRoutes(DeliveryRequest request) {
        List<HubRouteResponse> hubRoutes = deliveryExternalService.getHubRoutes(request);
        if (hubRoutes == null || hubRoutes.isEmpty()) {
            throw new CustomException(DeliveryErrorCode.NO_HUB_ROUTE);
        }
        return hubRoutes;
    }

    private UUID getDepartureHubId(List<HubRouteResponse> hubRoutes) {
        return hubRoutes.get(0).getDepartureHubId();
    }

    // Hub-Hub 경로가 아닌 원소의 경우 Hub-Company이므로 해당 경로의 출발 hub가 마지막 hub
    private UUID getDestinationHubId(List<HubRouteResponse> hubRoutes) {
        return hubRoutes.get(hubRoutes.size() - 1).getDepartureHubId();
    }
}
