package com.msa.delivery_service.client.user.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@NoArgsConstructor
public class DeliveryManagerResponse {

    private UUID deliveryManagerId;
    private UUID hubId;
    private String deliveryManagerName;
    private String deliveryManagerEmail;
    private String deliveryManagerSlackId;
    private String type;
    private Integer deliverySequence;
}
