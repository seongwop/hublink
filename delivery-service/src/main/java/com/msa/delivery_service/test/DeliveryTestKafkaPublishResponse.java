package com.msa.delivery_service.test;

import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

@Getter
@Builder
public class DeliveryTestKafkaPublishResponse {
    private String topic;
    private String eventKey;
    private UUID orderId;
    private String message;
}
