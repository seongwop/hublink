package com.msa.slack_service.service;

import com.msa.core_common.auth.UserRole;
import com.msa.slack_service.client.SlackClient;
import com.msa.slack_service.entity.SlackMessage;
import com.msa.slack_service.entity.SlackMessageStatus;
import com.msa.slack_service.exception.SlackErrorCode;
import com.msa.slack_service.stream.event.DeadlineGeneratedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.msa.core_common.error.exception.CustomException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SlackService {
    private final SlackMessageService slackMessageService;
    private final SlackClient slackClient;

    @Value("${slack.enabled:false}")
    private boolean slackEnabled;

    // 발송 시한 전송
    public void processDeadlineGenerated(DeadlineGeneratedEvent event) {
        // 멱등성 키로 중복 확인
        String idempotencyKey = event.getEventId().toString();
        SlackMessage slackMessage = slackMessageService.findOrCreateMessage(event, idempotencyKey);

        // 이미 보냈으면 전송 x
        if (slackMessage.getStatus() == SlackMessageStatus.SENT) {
            return;
        }

        sendAndUpdateStatus(slackMessage);
    }


    // 메세지 전송
    private void sendAndUpdateStatus(SlackMessage slackMessage) {
        // Slack enabled 설정이 false일 때 진행되는 로직
        if (!slackEnabled) {
            slackMessageService.markSkipped(
                    slackMessage.getSlackMessageId(),
                    "Slack 비활성화로 외부 API 호출 생략"
            );
            return;
        }

        try {
            slackClient.sendMessage(
                    slackMessage.getReceiverSlackId(),
                    slackMessage.getMessage()
            );
            slackMessageService.markSent(slackMessage.getSlackMessageId());
        } catch (Exception e) {
            slackMessageService.markFailed(slackMessage.getSlackMessageId(), e.getMessage());
            throw e;
        }
    }

    // 재전송
    public void resendSlackMessage(String role, UUID slackMessageId) {
        if (!UserRole.MASTER.name().equals(role)) {
            throw new CustomException(SlackErrorCode.SLACK_MESSAGE_ACCESS_DENIED);
        }

        SlackMessage slackMessage = slackMessageService.getEntity(slackMessageId);
        sendAndUpdateStatus(slackMessage);
    }
}
