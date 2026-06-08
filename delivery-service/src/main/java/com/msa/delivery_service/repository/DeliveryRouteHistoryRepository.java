package com.msa.delivery_service.repository;

import com.msa.delivery_service.entity.DeliveryRouteHistory;
import com.msa.delivery_service.enums.DeliveryRouteStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DeliveryRouteHistoryRepository extends JpaRepository<DeliveryRouteHistory, UUID> {

    // 특정 배송에 속한 경로 이력을 순번 오름차순으로 조회
    List<DeliveryRouteHistory> findByDeliveryDeliveryIdOrderBySequenceAsc(UUID deliveryId);

    // 특정 배송에 특정 배송 담당자가 배정된 경로 이력이 있는지 확인
    boolean existsByDeliveryDeliveryIdAndDeliveryManagerId(UUID deliveryId, UUID deliveryManagerId);

    // 아직 배송 중인 허브 배송 담당자 ID 목록을 조회
    @Query("""
        select rh.deliveryManagerId as managerId,
               count(rh) as assignmentCount
        from DeliveryRouteHistory rh
        where rh.deliveryManagerId in :managerIds
            and rh.deletedAt is null
            and rh.status not in :finishedStatuses
        group by rh.deliveryManagerId
    """)
    List<ManagerAssignmentCount> countActiveAssignmentsByManagerIds(
            @Param("managerIds") Collection<UUID> managerIds,
            @Param("finishedStatuses") Collection<DeliveryRouteStatus> finishedStatuses
    );
}
