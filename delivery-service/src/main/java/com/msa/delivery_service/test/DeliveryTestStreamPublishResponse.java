package com.msa.delivery_service.test;

import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

@Getter
@Builder
public class DeliveryTestStreamPublishResponse {
    private String streamKey;
    private String recordId;

    private UUID eventId;
    private UUID deliveryId;
    private UUID orderId;

    private UUID aiMessageId;
    private String message;
}
