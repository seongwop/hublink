package com.msa.ai_service.stream.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.ai_service.dto.AiDeadlineResult;
import com.msa.ai_service.entity.AiRequestType;
import com.msa.ai_service.exception.AiErrorCode;
import com.msa.ai_service.service.AiService;
import com.msa.ai_service.stream.event.DeadlineGeneratedEvent;
import com.msa.ai_service.stream.event.DeadlineNotificationRequestedEvent;
import com.msa.ai_service.stream.publisher.DeadlineGeneratedEventPublisher;
import com.msa.core_common.error.exception.CustomException;
import com.msa.core_common.stream.DeadlineStreamConstants;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeadlineNotificationRequestedPendingRetryConsumer {
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final AiService aiService;
    private final Validator validator;
    private final DeadlineGeneratedEventPublisher deadlineGeneratedEventPublisher;

    @Scheduled(fixedDelay = 300000)
    public void retryPendingMessages() {
        var records = stringRedisTemplate.opsForStream().read(
                Consumer.from(
                        DeadlineStreamConstants.AI_SERVICE_GROUP,
                        DeadlineStreamConstants.AI_SERVICE_CONSUMER
                ),
                StreamReadOptions.empty().count(10),
                StreamOffset.create(
                        DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM,
                        ReadOffset.from("0")
                )
        );

        if (records == null || records.isEmpty()) {
            return;
        }

        for (MapRecord<String, Object, Object> record : records) {
            try {
                log.info("event=AI_PENDING_RETRY_STARTED recordId={}", record.getId());

                Object payloadObj = record.getValue().get("payload");

                if (payloadObj == null) {
                    log.warn("event=AI_PENDING_PAYLOAD_MISSING recordId={}", record.getId());
                    acknowledge(record.getId());
                    continue;
                }

                DeadlineNotificationRequestedEvent event = objectMapper.readValue(
                        String.valueOf(payloadObj),
                        DeadlineNotificationRequestedEvent.class
                );

                Set<ConstraintViolation<DeadlineNotificationRequestedEvent>> violations =
                        validator.validate(event);

                if (!violations.isEmpty()) {
                    log.warn("event=AI_PENDING_VALIDATION_FAILED recordId={} violations={}",
                            record.getId(),
                            violations.stream()
                                    .map(ConstraintViolation::getMessage)
                                    .toList()
                    );
                    acknowledge(record.getId());
                    continue;
                }

                AiDeadlineResult result = aiService.generateDeadline(event);

                DeadlineGeneratedEvent generatedEvent = DeadlineGeneratedEvent.builder()
                        .eventId(UUID.randomUUID())
                        .deliveryId(event.getDeliveryId())
                        .aiMessageId(result.getAiMessageId())
                        .receiverUserId(event.getReceiverUserId())
                        .receiverSlackId(event.getReceiverSlackId())
                        .finalDepartureDeadline(result.getFinalDepartureDeadline())
                        .messageType(AiRequestType.DELIVERY_DEADLINE)
                        .message(result.getMessage())
                        .build();

                deadlineGeneratedEventPublisher.publish(generatedEvent);

                acknowledge(record.getId());

                log.info("event=AI_PENDING_ACK_COMPLETED recordId={}", record.getId());

            } catch (CustomException e) {
                if (e.getErrorCode() == AiErrorCode.AI_CIRCUIT_BREAKER_OPEN) {
                    log.warn("event=AI_PENDING_CIRCUIT_OPEN recordId={}", record.getId(), e);
                    continue;
                }
                log.error("event=AI_PENDING_FINAL_FAILED recordId={}", record.getId(), e);
                acknowledge(record.getId());
            } catch (Exception e) {
                log.error("event=AI_PENDING_RETRY_FAILED recordId={}", record.getId(), e);
                acknowledge(record.getId());
            }
        }
    }

    private void acknowledge(RecordId recordId) {
        stringRedisTemplate.opsForStream().acknowledge(
                DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM,
                DeadlineStreamConstants.AI_SERVICE_GROUP,
                recordId
        );
    }
}
