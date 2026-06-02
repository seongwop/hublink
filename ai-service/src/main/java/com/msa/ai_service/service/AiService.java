package com.msa.ai_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.ai_service.client.AiClient;
import com.msa.ai_service.dto.AiDeadlineResult;
import com.msa.ai_service.dto.AiResponse;
import com.msa.ai_service.entity.AiMessage;
import com.msa.ai_service.entity.AiRequestType;
import com.msa.ai_service.exception.AiErrorCode;
import com.msa.ai_service.parser.AiResponseParser;
import com.msa.ai_service.prompt.DeadlinePromptGenerator;
import com.msa.ai_service.stream.event.DeadlineNotificationRequestedEvent;
import com.msa.core_common.error.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AiService {
    private final AiClient aiClient;
    private final DeadlinePromptGenerator deadlinePromptGenerator;
    private final AiMessageService aiMessageService;
    private final ObjectMapper objectMapper;
    private final AiResponseParser aiResponseParser;

    @Value("${ai.enabled:false}")
    private boolean aiEnabled;

    // 발송 시한 생성
    public AiDeadlineResult generateDeadline(DeadlineNotificationRequestedEvent event) {
        String prompt = deadlinePromptGenerator.generatePrompt(event);

        AiMessage aiMessage = aiMessageService.saveMessage(
                event.getDeliveryId(),
                AiRequestType.DELIVERY_DEADLINE,
                prompt,
                toRequestPayload(event)
        );

        // AI enabled 설정이 false일 때 진행되는 로직
        if (!aiEnabled) {
            return skipExternalAi(event, aiMessage);
        }

        try {
            AiResponse response = aiClient.generate(prompt);

            if (response == null || response.getText() == null || response.getText().isBlank()) {
                throw new IllegalStateException("AI 응답이 비어 있습니다.");
            }

            AiDeadlineResult parsedResult =
                    aiResponseParser.parseDeadlineResponse(response.getText());

            aiMessageService.markCompleted(
                    aiMessage.getAiMessageId(),
                    parsedResult.getFinalDepartureDeadline(),
                    parsedResult.getMessage()
            );

            return AiDeadlineResult.of(
                    aiMessage.getAiMessageId(),
                    parsedResult.getFinalDepartureDeadline(),
                    parsedResult.getMessage()
            );

        } catch (Exception e) {
            aiMessageService.markFailed(aiMessage.getAiMessageId(), e.getMessage());
            throw e;
        }
    }
    private String toRequestPayload(DeadlineNotificationRequestedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new CustomException(AiErrorCode.AI_REQUEST_PAYLOAD_CONVERT_FAILED);
        }
    }

    private AiDeadlineResult skipExternalAi(
            DeadlineNotificationRequestedEvent event,
            AiMessage aiMessage
    ) {
        List<DeadlineNotificationRequestedEvent.RouteInfo> routeInfo = event.getRouteInfo();
        int totalDurationMinutes = routeInfo == null ? 0 : routeInfo.stream()
                .map(DeadlineNotificationRequestedEvent.RouteInfo::getEstimatedDurationMin)
                .filter(duration -> duration != null)
                .mapToInt(Integer::intValue)
                .sum();
        LocalDateTime requestedArrivalAt = event.getRequestedArrivalAt() == null
                ? LocalDateTime.now()
                : event.getRequestedArrivalAt();
        LocalDateTime fallbackDeadline = requestedArrivalAt.minusMinutes(totalDurationMinutes);
        String message = "AI 비활성화로 외부 API 호출 생략";

        aiMessageService.markSkipped(aiMessage.getAiMessageId(), fallbackDeadline, message);

        return AiDeadlineResult.of(
                aiMessage.getAiMessageId(),
                fallbackDeadline,
                message
        );
    }
}
