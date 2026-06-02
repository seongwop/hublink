package com.msa.slack_service.service;

import com.msa.core_common.auth.UserRole;
import com.msa.core_common.error.exception.CustomException;
import com.msa.slack_service.client.SlackClient;
import com.msa.slack_service.entity.MessageType;
import com.msa.slack_service.entity.SlackMessage;
import com.msa.slack_service.entity.SlackMessageStatus;
import com.msa.slack_service.exception.SlackErrorCode;
import com.msa.slack_service.stream.event.DeadlineGeneratedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

@ExtendWith(MockitoExtension.class)
class SlackServiceTest {
    @InjectMocks
    private SlackService slackService;

    @Mock
    private SlackMessageService slackMessageService;

    @Mock
    private SlackClient slackClient;

    private UUID slackMessageId;
    private DeadlineGeneratedEvent event;
    private SlackMessage pendingMessage;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(slackService, "slackEnabled", true);

        slackMessageId = UUID.randomUUID();

        event = new DeadlineGeneratedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "U123456",
                MessageType.DELIVERY_DEADLINE,
                "최종 발송 시한은 2026-05-30 15:00 입니다.",
                LocalDateTime.of(2026, 5, 30, 15, 0)
        );

        pendingMessage = SlackMessage.builder()
                .slackMessageId(slackMessageId)
                .receiverUserId(event.getReceiverUserId())
                .aiMessageId(event.getAiMessageId())
                .receiverSlackId(event.getReceiverSlackId())
                .idempotencyKey(event.getEventId().toString())
                .messageType(event.getMessageType())
                .message(event.getMessage())
                .status(SlackMessageStatus.PENDING)
                .build();
    }

    @Test
    @DisplayName("processDeadlineGenerated - PENDING 메시지 전송 성공")
    void processDeadlineGenerated_Success() {
        when(slackMessageService.findOrCreateMessage(event, event.getEventId().toString()))
                .thenReturn(pendingMessage);

        slackService.processDeadlineGenerated(event);

        verify(slackClient, times(1)).sendMessage(event.getReceiverSlackId(), event.getMessage());
        verify(slackMessageService, times(1)).markSent(slackMessageId);
        verify(slackMessageService, never()).markFailed(any(), anyString());
        verify(slackMessageService, never()).markSkipped(any(), anyString());
    }

    @Test
    @DisplayName("processDeadlineGenerated - Slack 비활성화 시 외부 API 호출 생략")
    void processDeadlineGenerated_SlackDisabled_SkipSend() {
        ReflectionTestUtils.setField(slackService, "slackEnabled", false);

        when(slackMessageService.findOrCreateMessage(event, event.getEventId().toString()))
                .thenReturn(pendingMessage);

        slackService.processDeadlineGenerated(event);

        verify(slackClient, never()).sendMessage(anyString(), anyString());
        verify(slackMessageService, times(1))
                .markSkipped(slackMessageId, "Slack 비활성화로 외부 API 호출 생략");
        verify(slackMessageService, never()).markSent(any());
        verify(slackMessageService, never()).markFailed(any(), anyString());
    }

    @Test
    @DisplayName("processDeadlineGenerated - 이미 SENT면 전송 생략")
    void processDeadlineGenerated_AlreadySent() {
        SlackMessage sentMessage = SlackMessage.builder()
                .slackMessageId(slackMessageId)
                .receiverSlackId(event.getReceiverSlackId())
                .message(event.getMessage())
                .status(SlackMessageStatus.SENT)
                .build();

        when(slackMessageService.findOrCreateMessage(event, event.getEventId().toString()))
                .thenReturn(sentMessage);

        slackService.processDeadlineGenerated(event);

        verify(slackClient, never()).sendMessage(anyString(), anyString());
        verify(slackMessageService, never()).markSent(any());
    }

    @Test
    @DisplayName("processDeadlineGenerated - 전송 실패 시 FAILED 처리 후 예외 전파")
    void processDeadlineGenerated_Fail_Send() {
        when(slackMessageService.findOrCreateMessage(event, event.getEventId().toString()))
                .thenReturn(pendingMessage);
        doThrow(new RuntimeException("slack api error"))
                .when(slackClient).sendMessage(event.getReceiverSlackId(), event.getMessage());

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> slackService.processDeadlineGenerated(event)
        );

        assertThat(exception.getMessage()).isEqualTo("slack api error");
        verify(slackMessageService, times(1)).markFailed(slackMessageId, "slack api error");
    }

    @Test
    @DisplayName("resendSlackMessage - MASTER 권한으로 재전송 성공")
    void resendSlackMessage_Success_Master() {
        when(slackMessageService.getEntity(slackMessageId)).thenReturn(pendingMessage);

        slackService.resendSlackMessage(UserRole.MASTER.name(), slackMessageId);

        verify(slackClient, times(1)).sendMessage(event.getReceiverSlackId(), event.getMessage());
        verify(slackMessageService, times(1)).markSent(slackMessageId);
    }

    @Test
    @DisplayName("resendSlackMessage - MASTER 권한이 아니면 접근 거부")
    void resendSlackMessage_Fail_NotMaster() {
        CustomException exception = assertThrows(
                CustomException.class,
                () -> slackService.resendSlackMessage(UserRole.HUB_MANAGER.name(), slackMessageId)
        );

        assertThat(exception.getErrorCode()).isEqualTo(SlackErrorCode.SLACK_MESSAGE_ACCESS_DENIED);
        verify(slackMessageService, never()).getEntity(any());
    }
}
