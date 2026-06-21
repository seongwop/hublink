package com.msa.delivery_service.entity;

import com.msa.delivery_service.enums.DeliveryAssignmentType;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class DeliveryAssignmentCountId implements Serializable {
    private UUID managerId;
    private DeliveryAssignmentType assignmentType;
}
