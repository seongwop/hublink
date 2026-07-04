package com.msa.delivery_service.repository;

import com.msa.delivery_service.entity.DeliveryAssignmentCount;
import com.msa.delivery_service.entity.DeliveryAssignmentCountId;
import com.msa.delivery_service.enums.DeliveryAssignmentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DeliveryAssignmentCountRepository
        extends JpaRepository<DeliveryAssignmentCount, DeliveryAssignmentCountId>,
        DeliveryAssignmentCountBulkRepository {

    @Query("""
        select dac.managerId as managerId,
               dac.activeAssignmentCount as assignmentCount
        from DeliveryAssignmentCount dac
        where dac.assignmentType = :assignmentType
            and dac.managerId in :managerIds
    """)
    List<ManagerAssignmentCount> findAssignmentCountsByManagerIds(
            @Param("managerIds") Collection<UUID> managerIds,
            @Param("assignmentType") DeliveryAssignmentType assignmentType
    );

    @Query(value = """
        select manager_id as "managerId",
               active_assignment_count as "assignmentCount"
        from delivery_service.p_delivery_assignment_counts
        where assignment_type = :assignmentType
          and manager_id in (:managerIds)
        order by manager_id
        for update
    """, nativeQuery = true)
    List<ManagerAssignmentCount> findAssignmentCountsByManagerIdsForUpdate(
            @Param("managerIds") Collection<UUID> managerIds,
            @Param("assignmentType") String assignmentType
    );

    @Modifying
    @Query(value = """
        update delivery_service.p_delivery_assignment_counts
        set active_assignment_count = greatest(active_assignment_count - :delta, 0)
        where manager_id = :managerId
          and assignment_type = :assignmentType
    """, nativeQuery = true)
    int decreaseAssignmentCount(
            @Param("managerId") UUID managerId,
            @Param("assignmentType") String assignmentType,
            @Param("delta") long delta
    );
}
