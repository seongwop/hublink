package com.msa.delivery_service.service;

import com.msa.delivery_service.enums.DeliveryAssignmentType;
import com.msa.delivery_service.repository.DeliveryAssignmentCountRepository;
import com.msa.delivery_service.repository.ManagerAssignmentCount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeliveryAssignmentCountService {

    private final DeliveryAssignmentCountRepository deliveryAssignmentCountRepository;

    @Transactional(readOnly = true)
    public Map<UUID, Long> getCompanyAssignmentCounts(Collection<UUID> managerIds) {
        return getAssignmentCounts(managerIds, DeliveryAssignmentType.COMPANY_DELIVERY);
    }

    @Transactional(readOnly = true)
    public Map<UUID, Long> getHubAssignmentCounts(Collection<UUID> managerIds) {
        return getAssignmentCounts(managerIds, DeliveryAssignmentType.HUB_DELIVERY);
    }

    @Transactional
    public void increaseCompanyAssignment(UUID managerId) {
        increaseAssignmentCounts(Map.of(managerId, 1L), DeliveryAssignmentType.COMPANY_DELIVERY);
    }

    @Transactional
    public void increaseHubAssignments(Collection<UUID> managerIds) {
        Map<UUID, Long> deltas = new HashMap<>();
        for (UUID managerId : managerIds) {
            deltas.merge(managerId, 1L, Long::sum);
        }
        increaseAssignmentCounts(deltas, DeliveryAssignmentType.HUB_DELIVERY);
    }

    @Transactional
    public void decreaseCompanyAssignment(UUID managerId) {
        deliveryAssignmentCountRepository.decreaseAssignmentCount(
                managerId,
                DeliveryAssignmentType.COMPANY_DELIVERY.name(),
                1L
        );
    }

    @Transactional
    public void decreaseHubAssignment(UUID managerId) {
        deliveryAssignmentCountRepository.decreaseAssignmentCount(
                managerId,
                DeliveryAssignmentType.HUB_DELIVERY.name(),
                1L
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
        for (ManagerAssignmentCount assignmentCount
                : deliveryAssignmentCountRepository.findAssignmentCountsByManagerIds(
                        managerIds,
                        assignmentType
                )) {
            countMap.put(assignmentCount.getManagerId(), assignmentCount.getAssignmentCount());
        }
        return countMap;
    }

    private void increaseAssignmentCounts(
            Map<UUID, Long> deltas,
            DeliveryAssignmentType assignmentType
    ) {
        for (Map.Entry<UUID, Long> entry : deltas.entrySet()) {
            deliveryAssignmentCountRepository.increaseAssignmentCount(
                    entry.getKey(),
                    assignmentType.name(),
                    entry.getValue()
            );
        }
    }
}
