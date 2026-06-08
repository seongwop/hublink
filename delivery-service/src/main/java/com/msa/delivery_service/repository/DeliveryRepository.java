package com.msa.delivery_service.repository;

import com.msa.delivery_service.entity.Delivery;
import com.msa.delivery_service.enums.DeliveryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

    boolean existsByOrderId(UUID orderId);

    Optional<Delivery> findByOrderId(UUID orderId);

    // 특정 업체 배송 담당자에게 배정된 배송 목록 조회
    Page<Delivery> findAllByCompanyDeliveryManagerId(UUID companyDeliveryManagerId, Pageable pageable);

    // 아직 배송 중인 업체 배송 담당자 ID 목록 조회
    @Query("""
        select d.companyDeliveryManagerId as managerId,
               count(d) as assignmentCount
        from Delivery d
        where d.companyDeliveryManagerId in :managerIds
            and d.deletedAt is null
            and d.status not in :finishedStatuses
        group by d.companyDeliveryManagerId
    """)
    List<ManagerAssignmentCount> countActiveAssignmentsByManagerIds(
            @Param("managerIds") Collection<UUID> managerIds,
            @Param("finishedStatuses") Collection<DeliveryStatus> finishedStatuses
    );
}
