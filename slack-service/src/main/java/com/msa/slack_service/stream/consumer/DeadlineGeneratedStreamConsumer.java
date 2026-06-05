package com.msa.slack_service.stream.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.core_common.stream.DeadlineStreamConstants;
import com.msa.slack_service.service.SlackService;
import com.msa.slack_service.stream.event.DeadlineGeneratedEvent;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeadlineGeneratedStreamConsumer {
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final SlackService slackService;
    private final Validator validator;

    @Scheduled(fixedDelay = 3000)
    public void consume() {
        var records = stringRedisTemplate.opsForStream().read(
                Consumer.from(
                        DeadlineStreamConstants.SLACK_SERVICE_GROUP,
                        DeadlineStreamConstants.SLACK_SERVICE_CONSUMER
                ),
                StreamReadOptions.empty().count(100),
                StreamOffset.create(
                        DeadlineStreamConstants.DEADLINE_GENERATED_STREAM,
                        ReadOffset.lastConsumed()
                )
        );

        if (records == null || records.isEmpty()) {
            return;
        }
        log.info("event=SLACK_STREAM_BATCH_RECEIVED stream={} count={}",
                DeadlineStreamConstants.DEADLINE_GENERATED_STREAM,
                records.size()
        );

        for (MapRecord<String, Object, Object> record : records) {
            try {
                Object payloadObj = record.getValue().get("payload");

                if (payloadObj == null) {
                    log.warn("event=SLACK_STREAM_PAYLOAD_MISSING recordId={}", record.getId());

                    stringRedisTemplate.opsForStream().acknowledge(
                            DeadlineStreamConstants.DEADLINE_GENERATED_STREAM,
                            DeadlineStreamConstants.SLACK_SERVICE_GROUP,
                            record.getId()
                    );

                    continue;
                }

                DeadlineGeneratedEvent event = objectMapper.readValue(
                        String.valueOf(payloadObj),
                        DeadlineGeneratedEvent.class
                );

                // 유효성 검증
                Set<ConstraintViolation<DeadlineGeneratedEvent>> violations = validator.validate(event);
                if (!violations.isEmpty()) {
                    log.warn("event=SLACK_STREAM_VALIDATION_FAILED recordId={} violations={}",
                            record.getId(),
                            violations.stream()
                                    .map(ConstraintViolation::getMessage)
                                    .toList()
                    );

                    stringRedisTemplate.opsForStream().acknowledge(
                            DeadlineStreamConstants.DEADLINE_GENERATED_STREAM,
                            DeadlineStreamConstants.SLACK_SERVICE_GROUP,
                            record.getId()
                    );

                    continue;
                }

                log.info("event=SLACK_STREAM_EVENT_RECEIVED eventId={} receiverSlackId={}",
                        event.getEventId(),
                        event.getReceiverSlackId()
                );

                slackService.processDeadlineGenerated(event);
                log.info("event=SLACK_DEADLINE_EVENT_PROCESSED recordId={} eventId={} deliveryId={} aiMessageId={}",
                        record.getId(),
                        event.getEventId(),
                        event.getDeliveryId(),
                        event.getAiMessageId()
                );

                stringRedisTemplate.opsForStream().acknowledge(
                        DeadlineStreamConstants.DEADLINE_GENERATED_STREAM,
                        DeadlineStreamConstants.SLACK_SERVICE_GROUP,
                        record.getId()
                );

                log.info("event=SLACK_STREAM_ACKED stream={} group={} recordId={}",
                        DeadlineStreamConstants.DEADLINE_GENERATED_STREAM,
                        DeadlineStreamConstants.SLACK_SERVICE_GROUP,
                        record.getId()
                );

            } catch (Exception e) {
                log.error("event=SLACK_STREAM_PROCESSING_FAILED recordId={}", record.getId(), e);
            }
        }
    }
}
