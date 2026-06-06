package com.msa.delivery_service.controller;

import com.msa.delivery_service.dto.DeliveryTestStreamPublishResponse;
import com.msa.delivery_service.message.DeadlineRequestedEvent;
import com.msa.delivery_service.service.DeliveryTestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Delivery Test API", description = "배송 테스트용 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/deliveries/test")
public class DeliveryTestController {

    private final DeliveryTestService deliveryTestService;

    @Operation(
            summary = "AI 시한 생성 요청 이벤트 발행",
            description = "DeadlineNotificationRequestedEvent를 Redis Streams에 발행하여 AI 비동기 처리 흐름을 테스트합니다."
    )
    @PostMapping("/deadline-requested")
    public DeliveryTestStreamPublishResponse publishDeadlineRequestedEvent(
            @RequestBody DeadlineRequestedEvent event
    ) {
        return deliveryTestService.publishDeadlineRequestedEvent(event);
    }
}