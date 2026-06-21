package com.msa.delivery_service.entity;

import com.msa.delivery_service.enums.DeliveryAssignmentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@Entity
@IdClass(DeliveryAssignmentCountId.class)
@Table(name = "p_delivery_assignment_counts", schema = "delivery_service")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DeliveryAssignmentCount {

    @Id
    @Column(name = "manager_id", nullable = false)
    private UUID managerId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_type", nullable = false, length = 50)
    private DeliveryAssignmentType assignmentType;

    @Column(name = "active_assignment_count", nullable = false)
    private Long activeAssignmentCount;
}
