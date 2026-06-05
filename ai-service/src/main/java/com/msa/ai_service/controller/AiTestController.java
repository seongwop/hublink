package com.msa.ai_service.controller;

import com.msa.ai_service.dto.AiTestStreamPublishResponse;
import com.msa.ai_service.service.AiTestService;
import com.msa.ai_service.stream.event.DeadlineGeneratedEvent;
import com.msa.ai_service.stream.event.DeadlineNotificationRequestedEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai/test")
@RequiredArgsConstructor
@Tag(name = "AI Test", description = "AI 비동기 이벤트 테스트 API")
public class AiTestController {
    private final AiTestService aiTestService;

    @Operation(
            summary = "AI 시한 생성 요청 이벤트 발행",
            description = "DeadlineNotificationRequestedEvent를 Redis Streams에 발행하여 AI 비동기 처리 흐름을 테스트합니다."
    )
    @PostMapping("/events/deadline-requested")
    public AiTestStreamPublishResponse publishDeadlineRequestedEvent(
            @RequestBody DeadlineNotificationRequestedEvent event
    ) {
        return aiTestService.publishDeadlineRequestedEvent(event);
    }

    @Operation(
            summary = "AI 시한 생성 완료 이벤트 발행",
            description = "DeadlineGeneratedEvent를 deadline:generated:stream에 직접 발행하여 AI → Slack/Delivery 흐름을 테스트합니다."
    )
    @PostMapping("/events/deadline-generated")
    public AiTestStreamPublishResponse publishDeadlineGeneratedEvent(
            @RequestBody DeadlineGeneratedEvent event
    ) {
        return aiTestService.publishDeadlineGeneratedEvent(event);
    }
}
