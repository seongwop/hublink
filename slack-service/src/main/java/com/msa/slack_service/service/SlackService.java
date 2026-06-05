package com.msa.slack_service.service;

import com.msa.core_common.auth.UserRole;
import com.msa.slack_service.client.SlackClient;
import com.msa.slack_service.entity.SlackMessage;
import com.msa.slack_service.entity.SlackMessageStatus;
import com.msa.slack_service.exception.SlackErrorCode;
import com.msa.slack_service.stream.event.DeadlineGeneratedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.msa.core_common.error.exception.CustomException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SlackService {
    private final SlackMessageService slackMessageService;
    private final SlackClient slackClient;

    @Value("${slack.enabled:false}")
    private boolean slackEnabled;

    // 발송 시한 전송
    public void processDeadlineGenerated(DeadlineGeneratedEvent event) {
        log.info("event=SLACK_DEADLINE_EVENT_PROCESSING eventId={} deliveryId={} aiMessageId={} receiverUserId={}",
                event.getEventId(),
                event.getDeliveryId(),
                event.getAiMessageId(),
                event.getReceiverUserId()
        );
        // 멱등성 키로 중복 확인
        String idempotencyKey = event.getEventId().toString();
        SlackMessage slackMessage = slackMessageService.findOrCreateMessage(event, idempotencyKey);

        // 이미 보냈으면 전송 x
        if (slackMessage.getStatus() == SlackMessageStatus.SENT) {
            log.info("event=SLACK_MESSAGE_ALREADY_SENT slackMessageId={} receiverSlackId={}",
                    slackMessage.getSlackMessageId(),
                    slackMessage.getReceiverSlackId()
            );
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
            log.info("event=SLACK_SEND_SKIPPED slackMessageId={} receiverSlackId={}",
                    slackMessage.getSlackMessageId(),
                    slackMessage.getReceiverSlackId()
            );
            return;
        }

        try {
            log.info("event=SLACK_SEND_REQUESTED slackMessageId={} receiverSlackId={}",
                    slackMessage.getSlackMessageId(),
                    slackMessage.getReceiverSlackId()
            );
            slackClient.sendMessage(
                    slackMessage.getReceiverSlackId(),
                    slackMessage.getMessage()
            );
            slackMessageService.markSent(slackMessage.getSlackMessageId());
            log.info("event=SLACK_SEND_SUCCEEDED slackMessageId={} receiverSlackId={}",
                    slackMessage.getSlackMessageId(),
                    slackMessage.getReceiverSlackId()
            );
        } catch (Exception e) {
            slackMessageService.markFailed(slackMessage.getSlackMessageId(), e.getMessage());
            log.error("event=SLACK_SEND_FAILED slackMessageId={} receiverSlackId={}",
                    slackMessage.getSlackMessageId(),
                    slackMessage.getReceiverSlackId(),
                    e
            );
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
