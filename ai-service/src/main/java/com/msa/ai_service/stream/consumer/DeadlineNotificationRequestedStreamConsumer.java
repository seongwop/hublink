package com.msa.ai_service.stream.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.ai_service.dto.AiDeadlineResult;
import com.msa.ai_service.entity.AiRequestType;
import com.msa.ai_service.service.AiService;
import com.msa.ai_service.stream.event.DeadlineGeneratedEvent;
import com.msa.ai_service.stream.event.DeadlineNotificationRequestedEvent;
import com.msa.ai_service.stream.publisher.DeadlineGeneratedEventPublisher;
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
public class DeadlineNotificationRequestedStreamConsumer {
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final AiService aiService;
    private final Validator validator;
    private final DeadlineGeneratedEventPublisher deadlineGeneratedEventPublisher;

    @Scheduled(fixedDelay = 3000)
    public void consume() {
        var records = stringRedisTemplate.opsForStream().read(
                Consumer.from(
                        DeadlineStreamConstants.AI_SERVICE_GROUP,
                        DeadlineStreamConstants.AI_SERVICE_CONSUMER
                ),
                StreamReadOptions.empty().count(100),
                StreamOffset.create(
                        DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM,
                        ReadOffset.lastConsumed()
                )
        );

        if (records == null || records.isEmpty()) {
            return;
        }
        log.info("event=AI_STREAM_BATCH_RECEIVED stream={} count={}",
                DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM,
                records.size()
        );

        for (MapRecord<String, Object, Object> record : records) {
            try {
                Object payloadObj = record.getValue().get("payload");

                if (payloadObj == null) {
                    log.warn("event=AI_STREAM_PAYLOAD_MISSING recordId={}", record.getId());

                    stringRedisTemplate.opsForStream().acknowledge(
                            DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM,
                            DeadlineStreamConstants.AI_SERVICE_GROUP,
                            record.getId()
                    );

                    continue;
                }

                DeadlineNotificationRequestedEvent event = objectMapper.readValue(
                        String.valueOf(payloadObj),
                        DeadlineNotificationRequestedEvent.class
                );

                Set<ConstraintViolation<DeadlineNotificationRequestedEvent>> violations =
                        validator.validate(event);

                if (!violations.isEmpty()) {
                    log.warn("event=AI_STREAM_VALIDATION_FAILED recordId={} violations={}",
                            record.getId(),
                            violations.stream()
                                    .map(ConstraintViolation::getMessage)
                                    .toList()
                    );

                    stringRedisTemplate.opsForStream().acknowledge(
                            DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM,
                            DeadlineStreamConstants.AI_SERVICE_GROUP,
                            record.getId()
                    );

                    continue;
                }

                log.info("event=AI_STREAM_EVENT_RECEIVED eventId={} deliveryId={} orderId={}",
                        event.getEventId(),
                        event.getDeliveryId(),
                        event.getOrderId()
                );

                AiDeadlineResult result = aiService.generateDeadline(event);
                log.info("event=AI_DEADLINE_REQUEST_PROCESSED recordId={} eventId={} deliveryId={} orderId={}",
                        record.getId(),
                        event.getEventId(),
                        event.getDeliveryId(),
                        event.getOrderId()
                );

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

                RecordId generatedRecordId = deadlineGeneratedEventPublisher.publish(generatedEvent);
                log.info("event=AI_DEADLINE_GENERATED_ENQUEUED sourceRecordId={} generatedRecordId={} deliveryId={} aiMessageId={}",
                        record.getId(),
                        generatedRecordId,
                        generatedEvent.getDeliveryId(),
                        generatedEvent.getAiMessageId()
                );

                stringRedisTemplate.opsForStream().acknowledge(
                        DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM,
                        DeadlineStreamConstants.AI_SERVICE_GROUP,
                        record.getId()
                );

                log.info("event=AI_STREAM_ACK_COMPLETED recordId={}", record.getId());
                log.info("event=AI_STREAM_ACKED stream={} group={} recordId={}",
                        DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM,
                        DeadlineStreamConstants.AI_SERVICE_GROUP,
                        record.getId()
                );

            } catch (Exception e) {
                log.error("event=AI_STREAM_PROCESSING_FAILED recordId={}", record.getId(), e);
            }
        }
    }
}
