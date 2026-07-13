package com.msa.delivery_service.service;

import com.msa.core_common.error.exception.CustomException;
import com.msa.delivery_service.enums.DeliveryAssignmentType;
import com.msa.delivery_service.enums.DeliveryErrorCode;
import com.msa.delivery_service.repository.DeliveryAssignmentCountRepository;
import com.msa.delivery_service.repository.ManagerAssignmentCount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeliveryAssignmentCountService {

    private final DeliveryAssignmentCountRepository deliveryAssignmentCountRepository;
    // 배송 담당자 배정 집계 처리 시간 계측
    private final DeliveryPerformanceMetrics performanceMetrics;

    @Transactional(readOnly = true)
    public Map<UUID, Long> getCompanyAssignmentCounts(Collection<UUID> managerIds) {
        return getAssignmentCounts(managerIds, DeliveryAssignmentType.COMPANY_DELIVERY);
    }

    @Transactional(readOnly = true)
    public Map<UUID, Long> getHubAssignmentCounts(Collection<UUID> managerIds) {
        return getAssignmentCounts(managerIds, DeliveryAssignmentType.HUB_DELIVERY);
    }

    @Transactional
    public UUID reserveCompanyAssignment(Collection<UUID> managerIds, long maxActiveAssignments) {
        return reserveAssignment(
                managerIds,
                DeliveryAssignmentType.COMPANY_DELIVERY,
                maxActiveAssignments
        );
    }

    @Transactional
    public UUID reserveHubAssignment(Collection<UUID> managerIds, long maxActiveAssignments) {
        return reserveAssignment(
                managerIds,
                DeliveryAssignmentType.HUB_DELIVERY,
                maxActiveAssignments
        );
    }

    @Transactional
    public void increaseCompanyAssignment(UUID managerId) {
        increaseAssignmentCounts(Map.of(managerId, 1L), DeliveryAssignmentType.COMPANY_DELIVERY);
    }

    @Transactional
    public void increaseHubAssignments(Collection<UUID> managerIds) {
        increaseAssignmentCounts(toDeltas(managerIds), DeliveryAssignmentType.HUB_DELIVERY);
    }

    @Transactional
    public void increaseDeliveryAssignments(
            UUID companyDeliveryManagerId,
            Collection<UUID> hubDeliveryManagerIds
    ) {
        Map<DeliveryAssignmentType, Map<UUID, Long>> deltasByAssignmentType =
                new EnumMap<>(DeliveryAssignmentType.class);
        deltasByAssignmentType.put(
                DeliveryAssignmentType.COMPANY_DELIVERY,
                Map.of(companyDeliveryManagerId, 1L)
        );

        Map<UUID, Long> hubDeltas = toDeltas(hubDeliveryManagerIds);
        if (!hubDeltas.isEmpty()) {
            deltasByAssignmentType.put(DeliveryAssignmentType.HUB_DELIVERY, hubDeltas);
        }

        // 諛곗넚 ?대떦??諛곗젙 吏묎퀎 利앷? 泥섎━ ?쒓컙 怨꾩륫
        performanceMetrics.recordAssignmentCountOperation(
                "mixed",
                "increase",
                () -> deliveryAssignmentCountRepository.increaseAssignmentCounts(deltasByAssignmentType)
        );
    }

    @Transactional
    public void decreaseDeliveryAssignments(
            UUID companyDeliveryManagerId,
            Collection<UUID> hubDeliveryManagerIds
    ) {
        Map<UUID, Long> hubDeltas = toDeltas(hubDeliveryManagerIds);

        performanceMetrics.recordAssignmentCountOperation(
                "mixed",
                "reservation_compensate",
                () -> {
                    deliveryAssignmentCountRepository.decreaseAssignmentCount(
                            companyDeliveryManagerId,
                            DeliveryAssignmentType.COMPANY_DELIVERY.name(),
                            1L
                    );
                    for (Map.Entry<UUID, Long> hubDelta : hubDeltas.entrySet()) {
                        deliveryAssignmentCountRepository.decreaseAssignmentCount(
                                hubDelta.getKey(),
                                DeliveryAssignmentType.HUB_DELIVERY.name(),
                                hubDelta.getValue()
                        );
                    }
                }
        );
    }

    @Transactional
    public void decreaseCompanyAssignment(UUID managerId) {
        // 업체 배송 담당자 집계 감소 처리 시간 계측
        performanceMetrics.recordAssignmentCountOperation(
                DeliveryAssignmentType.COMPANY_DELIVERY.name(),
                "decrease",
                () -> deliveryAssignmentCountRepository.decreaseAssignmentCount(
                        managerId,
                        DeliveryAssignmentType.COMPANY_DELIVERY.name(),
                        1L
                )
        );
    }

    @Transactional
    public void decreaseHubAssignment(UUID managerId) {
        // 허브 배송 담당자 집계 감소 처리 시간 계측
        performanceMetrics.recordAssignmentCountOperation(
                DeliveryAssignmentType.HUB_DELIVERY.name(),
                "decrease",
                () -> deliveryAssignmentCountRepository.decreaseAssignmentCount(
                        managerId,
                        DeliveryAssignmentType.HUB_DELIVERY.name(),
                        1L
                )
        );
    }

    private Map<UUID, Long> getAssignmentCounts(
            Collection<UUID> managerIds,
            DeliveryAssignmentType assignmentType
    ) {
        if (managerIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Long> countMap = new HashMap<>();
        // 배송 담당자 배정 집계 조회 처리 시간 계측
        for (ManagerAssignmentCount assignmentCount
                : performanceMetrics.recordAssignmentCountOperation(
                        assignmentType.name(),
                        "read",
                        () -> deliveryAssignmentCountRepository.findAssignmentCountsByManagerIds(
                                managerIds,
                                assignmentType
                        )
                )) {
            countMap.put(assignmentCount.getManagerId(), assignmentCount.getAssignmentCount());
        }
        return countMap;
    }

    private UUID reserveAssignment(
            Collection<UUID> managerIds,
            DeliveryAssignmentType assignmentType,
            long maxActiveAssignments
    ) {
        if (managerIds.isEmpty()) {
            throw new CustomException(DeliveryErrorCode.NO_DELIVERY_MANAGER);
        }

        // 배송 담당자 배정 집계 row lock 조회 처리 시간 계측
        // 배송 담당자 선택과 집계 증가를 하나의 쿼리로 처리
        return performanceMetrics.recordAssignmentCountOperation(
                assignmentType.name(),
                "reserve_atomic_skip_locked",
                () -> deliveryAssignmentCountRepository.reserveAssignment(
                        managerIds,
                        assignmentType,
                        maxActiveAssignments
                ).orElseThrow(() -> new CustomException(DeliveryErrorCode.NO_DELIVERY_MANAGER))
        );
    }

    private void increaseAssignmentCounts(
            Map<UUID, Long> deltas,
            DeliveryAssignmentType assignmentType
    ) {
        // 배송 담당자 배정 집계 증가 처리 시간 계측
        performanceMetrics.recordAssignmentCountOperation(
                assignmentType.name(),
                "increase",
                () -> deliveryAssignmentCountRepository.increaseAssignmentCounts(deltas, assignmentType)
        );
    }

    private Map<UUID, Long> toDeltas(Collection<UUID> managerIds) {
        Map<UUID, Long> deltas = new HashMap<>();
        for (UUID managerId : managerIds) {
            deltas.merge(managerId, 1L, Long::sum);
        }
        return deltas;
    }
}
