package com.msa.delivery_service.repository;

import com.msa.delivery_service.enums.DeliveryAssignmentType;

import java.util.Map;
import java.util.UUID;

public interface DeliveryAssignmentCountBulkRepository {

    void increaseAssignmentCounts(Map<UUID, Long> deltas, DeliveryAssignmentType assignmentType);

    void increaseAssignmentCounts(Map<DeliveryAssignmentType, Map<UUID, Long>> deltasByAssignmentType);
}
