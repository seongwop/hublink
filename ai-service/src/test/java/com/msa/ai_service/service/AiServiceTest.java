package com.msa.ai_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiServiceTest {
    @InjectMocks
    private AiService aiService;

    @Mock
    private AiClient aiClient;

    @Mock
    private DeadlinePromptGenerator deadlinePromptGenerator;

    @Mock
    private AiMessageService aiMessageService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private AiResponseParser aiResponseParser;

    private DeadlineNotificationRequestedEvent event;
    private UUID aiMessageId;
    private UUID deliveryId;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(aiService, "aiEnabled", true);

        aiMessageId = UUID.randomUUID();
        deliveryId = UUID.randomUUID();

        event = DeadlineNotificationRequestedEvent.builder()
                .eventId(UUID.randomUUID())
                .deliveryId(deliveryId)
                .orderId(UUID.randomUUID())
                .ordererName("홍길동")
                .ordererEmail("hong@test.com")
                .orderedAt(LocalDateTime.of(2026, 5, 29, 9, 0))
                .requestMessage("문 앞 배송")
                .receiverUserId(UUID.randomUUID())
                .receiverSlackId("U123456")
                .products(List.of(DeadlineNotificationRequestedEvent.ProductInfo.builder()
                        .productName("콜라")
                        .quantity(2)
                        .build()))
                .requestedArrivalAt(LocalDateTime.of(2026, 5, 30, 18, 0))
                .departureHubName("서울 허브")
                .destinationAddress("서울시 강남구")
                .deliveryManagerName("김매니저")
                .deliveryManagerEmail("manager@test.com")
                .routeInfo(List.of(DeadlineNotificationRequestedEvent.RouteInfo.builder()
                        .departureHubName("서울 허브")
                        .arrivalHubName("강남 허브")
                        .estimatedDistanceKm(10.0)
                        .estimatedDurationMin(30)
                        .routeType("HUB_TO_HUB")
                        .build()))
                .workStartTime(LocalTime.of(9, 0))
                .workEndTime(LocalTime.of(18, 0))
                .build();
    }

    @Test
    @DisplayName("generateDeadline - AI 호출 성공 시 완료 처리 및 결과 반환")
    void generateDeadline_Success() throws JsonProcessingException {
        String prompt = "프롬프트";
        String payload = "{\"deliveryId\":\"123\"}";
        LocalDateTime deadline = LocalDateTime.of(2026, 5, 30, 15, 0);
        String message = "최종 발송 시한은 2026-05-30 15:00 입니다.";

        AiMessage savedMessage = AiMessage.builder()
                .aiMessageId(aiMessageId)
                .deliveryId(deliveryId)
                .requestType(AiRequestType.DELIVERY_DEADLINE)
                .prompt(prompt)
                .requestPayload(payload)
                .build();

        AiResponse aiResponse = mock(AiResponse.class);

        when(deadlinePromptGenerator.generatePrompt(event)).thenReturn(prompt);
        when(objectMapper.writeValueAsString(event)).thenReturn(payload);
        when(aiMessageService.saveMessage(deliveryId, AiRequestType.DELIVERY_DEADLINE, prompt, payload))
                .thenReturn(savedMessage);
        when(aiClient.generate(prompt)).thenReturn(aiResponse);
        when(aiResponse.getText()).thenReturn("{json}");
        when(aiResponseParser.parseDeadlineResponse("{json}"))
                .thenReturn(AiDeadlineResult.of(null, deadline, message));

        AiDeadlineResult result = aiService.generateDeadline(event);

        assertThat(result.getAiMessageId()).isEqualTo(aiMessageId);
        assertThat(result.getFinalDepartureDeadline()).isEqualTo(deadline);
        assertThat(result.getMessage()).isEqualTo(message);

        verify(aiMessageService, times(1)).markCompleted(aiMessageId, deadline, message);
        verify(aiMessageService, never()).markFailed(any(), anyString());
        verify(aiMessageService, never()).markSkipped(any(), any(), anyString());
    }

    @Test
    @DisplayName("generateDeadline - AI 비활성화 시 외부 API 호출 생략")
    void generateDeadline_AiDisabled_SkipGenerate() throws JsonProcessingException {
        ReflectionTestUtils.setField(aiService, "aiEnabled", false);

        String prompt = "?꾨＼?꾪듃";
        String payload = "{\"deliveryId\":\"123\"}";
        LocalDateTime fallbackDeadline = LocalDateTime.of(2026, 5, 30, 17, 30);
        String message = "AI 비활성화로 외부 API 호출 생략";

        AiMessage savedMessage = AiMessage.builder()
                .aiMessageId(aiMessageId)
                .deliveryId(deliveryId)
                .requestType(AiRequestType.DELIVERY_DEADLINE)
                .prompt(prompt)
                .requestPayload(payload)
                .build();

        when(deadlinePromptGenerator.generatePrompt(event)).thenReturn(prompt);
        when(objectMapper.writeValueAsString(event)).thenReturn(payload);
        when(aiMessageService.saveMessage(deliveryId, AiRequestType.DELIVERY_DEADLINE, prompt, payload))
                .thenReturn(savedMessage);

        AiDeadlineResult result = aiService.generateDeadline(event);

        assertThat(result.getAiMessageId()).isEqualTo(aiMessageId);
        assertThat(result.getFinalDepartureDeadline()).isEqualTo(fallbackDeadline);
        assertThat(result.getMessage()).isEqualTo(message);

        verify(aiClient, never()).generate(anyString());
        verify(aiMessageService, times(1)).markSkipped(aiMessageId, fallbackDeadline, message);
        verify(aiMessageService, never()).markCompleted(any(), any(), anyString());
        verify(aiMessageService, never()).markFailed(any(), anyString());
    }

    @Test
    @DisplayName("generateDeadline - AI 응답이 비어있으면 실패 처리 후 예외 발생")
    void generateDeadline_Fail_EmptyAiResponse() throws JsonProcessingException {
        String prompt = "프롬프트";
        String payload = "{\"deliveryId\":\"123\"}";

        AiMessage savedMessage = AiMessage.builder()
                .aiMessageId(aiMessageId)
                .deliveryId(deliveryId)
                .requestType(AiRequestType.DELIVERY_DEADLINE)
                .prompt(prompt)
                .requestPayload(payload)
                .build();

        AiResponse aiResponse = mock(AiResponse.class);

        when(deadlinePromptGenerator.generatePrompt(event)).thenReturn(prompt);
        when(objectMapper.writeValueAsString(event)).thenReturn(payload);
        when(aiMessageService.saveMessage(deliveryId, AiRequestType.DELIVERY_DEADLINE, prompt, payload))
                .thenReturn(savedMessage);
        when(aiClient.generate(prompt)).thenReturn(aiResponse);
        when(aiResponse.getText()).thenReturn("   ");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> aiService.generateDeadline(event)
        );

        assertThat(exception.getMessage()).isEqualTo("AI 응답이 비어 있습니다.");
        verify(aiMessageService, times(1)).markFailed(eq(aiMessageId), eq("AI 응답이 비어 있습니다."));
        verify(aiMessageService, never()).markCompleted(any(), any(), anyString());
    }

    @Test
    @DisplayName("generateDeadline - 요청 payload 변환 실패 시 커스텀 예외 발생")
    void generateDeadline_Fail_RequestPayloadConvert() throws JsonProcessingException {
        when(deadlinePromptGenerator.generatePrompt(event)).thenReturn("프롬프트");
        when(objectMapper.writeValueAsString(event))
                .thenThrow(new JsonProcessingException("직렬화 실패") {
                });

        CustomException exception = assertThrows(
                CustomException.class,
                () -> aiService.generateDeadline(event)
        );

        assertThat(exception.getErrorCode()).isEqualTo(AiErrorCode.AI_REQUEST_PAYLOAD_CONVERT_FAILED);
        verify(aiMessageService, never()).saveMessage(any(), any(), anyString(), anyString());
    }
}
