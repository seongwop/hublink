package com.msa.ai_service.stream.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.ai_service.dto.AiDeadlineResult;
import com.msa.ai_service.entity.AiRequestType;
import com.msa.ai_service.service.AiService;
import com.msa.ai_service.stream.event.DeadlineGeneratedEvent;
import com.msa.ai_service.stream.event.DeadlineNotificationRequestedEvent;
import com.msa.ai_service.stream.publisher.DeadlineGeneratedEventPublisher;
import com.msa.core_common.stream.DeadlineStreamConstants;
import jakarta.annotation.PostConstruct;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
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

    @Qualifier("aiConsumerTaskScheduler")
    private final TaskScheduler taskScheduler;

    @Value("${ai.stream.consumer.deadline-requested.read-count:300}")
    private int readCount;

    @Value("${ai.stream.consumer.deadline-requested.fixed-delay-ms:3000}")
    private long fixedDelayMs;

    @Value("${ai.stream.consumer.deadline-requested.concurrency:1}")
    private int concurrency;

    @PostConstruct
    public void startConsumers() {
        for (int i = 1; i <= concurrency; i++) {
            String consumerName = DeadlineStreamConstants.AI_SERVICE_CONSUMER + "-" + i;

            taskScheduler.scheduleWithFixedDelay(
                    () -> consume(consumerName),
                    Duration.ofMillis(fixedDelayMs)
            );

            log.info("event=AI_STREAM_CONSUMER_STARTED stream={} group={} consumerName={} fixedDelayMs={} readCount={}",
                    DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM,
                    DeadlineStreamConstants.AI_SERVICE_GROUP,
                    consumerName,
                    fixedDelayMs,
                    readCount
            );
        }
    }

    private void consume(String consumerName) {
        var records = stringRedisTemplate.opsForStream().read(
                Consumer.from(
                        DeadlineStreamConstants.AI_SERVICE_GROUP,
                        consumerName
                ),
                StreamReadOptions.empty().count(readCount),
                StreamOffset.create(
                        DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM,
                        ReadOffset.lastConsumed()
                )
        );

        if (records == null || records.isEmpty()) {
            return;
        }

        log.info("event=AI_STREAM_BATCH_RECEIVED stream={} group={} consumerName={} count={} readCount={}",
                DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM,
                DeadlineStreamConstants.AI_SERVICE_GROUP,
                consumerName,
                records.size(),
                readCount
        );

        for (MapRecord<String, Object, Object> record : records) {
            try {
                Object payloadObj = record.getValue().get("payload");

                if (payloadObj == null) {
                    log.warn("event=AI_STREAM_PAYLOAD_MISSING consumerName={} recordId={}",
                            consumerName,
                            record.getId()
                    );

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
                    log.warn("event=AI_STREAM_VALIDATION_FAILED consumerName={} recordId={} violations={}",
                            consumerName,
                            record.getId(),
                            violations.stream()
                                    .map(ConstraintViolation::getMessage)
                                    .toList()
                    );

                    acknowledge(record.getId());
                    continue;
                }

                log.debug("event=AI_STREAM_EVENT_RECEIVED consumerName={} eventId={} deliveryId={} orderId={}",
                        consumerName,
                        event.getEventId(),
                        event.getDeliveryId(),
                        event.getOrderId()
                );

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

                RecordId generatedRecordId = deadlineGeneratedEventPublisher.publish(generatedEvent);

                log.debug("event=AI_DEADLINE_GENERATED_ENQUEUED consumerName={} sourceRecordId={} generatedRecordId={} deliveryId={} aiMessageId={}",
                        consumerName,
                        record.getId(),
                        generatedRecordId,
                        generatedEvent.getDeliveryId(),
                        generatedEvent.getAiMessageId()
                );

                acknowledge(record.getId());

                log.debug("event=AI_STREAM_ACKED stream={} group={} consumerName={} recordId={}",
                        DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM,
                        DeadlineStreamConstants.AI_SERVICE_GROUP,
                        consumerName,
                        record.getId()
                );

            } catch (Exception e) {
                log.error("event=AI_STREAM_PROCESSING_FAILED consumerName={} recordId={}",
                        consumerName,
                        record.getId(),
                        e
                );
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