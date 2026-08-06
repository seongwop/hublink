package com.msa.delivery_service.controller;

import com.msa.delivery_service.dto.DeliveryRequest;
import com.msa.delivery_service.dto.DeliveryTestKafkaPublishResponse;
import com.msa.delivery_service.dto.DeliveryTestStreamPublishResponse;
import com.msa.delivery_service.message.DeadlineRequestedEvent;
import com.msa.delivery_service.service.DeliveryTestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Delivery Test API", description = "배송 테스트용 API")
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "delivery.test-api",
        name = "enabled",
        havingValue = "true"
)
@RequestMapping("/api/v1/deliveries/test")
public class DeliveryTestController {

    private final DeliveryTestService deliveryTestService;

    @Operation(
            summary = "delivery.create 이벤트 발행",
            description = "DeliveryRequest를 Kafka delivery.create 토픽에 발행하여 배송 생성 consumer 처리량을 테스트합니다."
    )
    @PostMapping("/delivery-create")
    public DeliveryTestKafkaPublishResponse publishDeliveryCreateEvent(
            @Valid @RequestBody DeliveryRequest request
    ) {
        return deliveryTestService.publishDeliveryCreateEvent(request);
    }

    @Operation(
            summary = "AI 마감 생성 요청 이벤트 발행",
            description = "DeadlineRequestedEvent를 Redis Streams에 발행하여 AI 비동기 처리 흐름을 테스트합니다."
    )
    @PostMapping("/deadline-requested")
    public DeliveryTestStreamPublishResponse publishDeadlineRequestedEvent(
            @RequestBody DeadlineRequestedEvent event
    ) {
        return deliveryTestService.publishDeadlineRequestedEvent(event);
    }
}
