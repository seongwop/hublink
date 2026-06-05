package com.msa.ai_service.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

@Getter
@Builder
public class AiTestStreamPublishResponse {
    private String streamKey;
    private String recordId;

    private UUID eventId;
    private UUID deliveryId;
    private UUID orderId;

    private UUID aiMessageId;
    private String message;
}