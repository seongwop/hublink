package com.msa.ai_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.ai_service.dto.AiTestStreamPublishResponse;
import com.msa.ai_service.exception.AiErrorCode;
import com.msa.ai_service.stream.event.DeadlineGeneratedEvent;
import com.msa.ai_service.stream.event.DeadlineNotificationRequestedEvent;
import com.msa.ai_service.stream.publisher.DeadlineGeneratedEventPublisher;
import com.msa.core_common.error.exception.CustomException;
import com.msa.core_common.stream.DeadlineStreamConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiTestService {
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final DeadlineGeneratedEventPublisher deadlineGeneratedEventPublisher;

    public AiTestStreamPublishResponse publishDeadlineRequestedEvent(
            DeadlineNotificationRequestedEvent event
    ) {
        String payload = toPayload(event);

        RecordId recordId = stringRedisTemplate.opsForStream().add(
                MapRecord.create(
                        DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM,
                        Map.of("payload", payload)
                )
        );

        log.info("event=AI_TEST_EVENT_PUBLISHED stream={} recordId={} eventId={} deliveryId={} orderId={}",
                DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM,
                recordId,
                event.getEventId(),
                event.getDeliveryId(),
                event.getOrderId()
        );

        return AiTestStreamPublishResponse.builder()
                .streamKey(DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM)
                .recordId(recordId == null ? null : recordId.getValue())
                .eventId(event.getEventId())
                .deliveryId(event.getDeliveryId())
                .orderId(event.getOrderId())
                .message("DeadlineNotificationRequestedEvent published")
                .build();
    }
    public AiTestStreamPublishResponse publishDeadlineGeneratedEvent(
            DeadlineGeneratedEvent event
    ) {
        RecordId recordId = deadlineGeneratedEventPublisher.publish(event);

        log.info("event=AI_TEST_DEADLINE_GENERATED_EVENT_PUBLISHED stream={} recordId={} eventId={} deliveryId={} aiMessageId={}",
                DeadlineStreamConstants.DEADLINE_GENERATED_STREAM,
                recordId,
                event.getEventId(),
                event.getDeliveryId(),
                event.getAiMessageId()
        );

        return AiTestStreamPublishResponse.builder()
                .streamKey(DeadlineStreamConstants.DEADLINE_GENERATED_STREAM)
                .recordId(recordId == null ? null : recordId.getValue())
                .eventId(event.getEventId())
                .deliveryId(event.getDeliveryId())
                .aiMessageId(event.getAiMessageId())
                .message("DeadlineGeneratedEvent published")
                .build();
    }

    private String toPayload(DeadlineNotificationRequestedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.error("event=AI_TEST_EVENT_PAYLOAD_CONVERT_FAILED eventId={}",
                    event.getEventId(),
                    e
            );
            throw new CustomException(AiErrorCode.AI_REQUEST_PAYLOAD_CONVERT_FAILED);
        }
    }
}
