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
        extends JpaRepository<DeliveryAssignmentCount, DeliveryAssignmentCountId> {

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

    @Modifying
    @Query(value = """
        insert into delivery_service.p_delivery_assignment_counts (
            manager_id,
            assignment_type,
            active_assignment_count,
            created_at,
            created_by,
            updated_at,
            updated_by
        )
        values (
            :managerId,
            :assignmentType,
            :delta,
            now(),
            'SYSTEM',
            now(),
            'SYSTEM'
        )
        on conflict (manager_id, assignment_type)
        do update set active_assignment_count =
                delivery_service.p_delivery_assignment_counts.active_assignment_count + excluded.active_assignment_count,
            updated_at = now(),
            updated_by = 'SYSTEM'
    """, nativeQuery = true)
    void increaseAssignmentCount(
            @Param("managerId") UUID managerId,
            @Param("assignmentType") String assignmentType,
            @Param("delta") long delta
    );

    @Modifying
    @Query(value = """
        update delivery_service.p_delivery_assignment_counts
        set active_assignment_count = greatest(active_assignment_count - :delta, 0),
            updated_at = now(),
            updated_by = 'SYSTEM'
        where manager_id = :managerId
          and assignment_type = :assignmentType
    """, nativeQuery = true)
    int decreaseAssignmentCount(
            @Param("managerId") UUID managerId,
            @Param("assignmentType") String assignmentType,
            @Param("delta") long delta
    );
}
