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
public class DeadlineGeneratedPendingRetryConsumer {
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final SlackService slackService;
    private final Validator validator;

    @Scheduled(fixedDelay = 300000)
    public void retryPendingMessages() {
        var records = stringRedisTemplate.opsForStream().read(
                Consumer.from(
                        DeadlineStreamConstants.SLACK_SERVICE_GROUP,
                        DeadlineStreamConstants.SLACK_SERVICE_CONSUMER
                ),
                StreamReadOptions.empty().count(100),
                StreamOffset.create(
                        DeadlineStreamConstants.DEADLINE_GENERATED_STREAM,
                        ReadOffset.from("0")
                )
        );

        if (records == null || records.isEmpty()) {
            return;
        }

        for (MapRecord<String, Object, Object> record : records) {
            try {
                Object payloadObj = record.getValue().get("payload");

                if (payloadObj == null) {
                    log.warn("event=SLACK_PENDING_PAYLOAD_MISSING recordId={}", record.getId());
                    acknowledge(record);
                    continue;
                }

                DeadlineGeneratedEvent event = objectMapper.readValue(
                        String.valueOf(payloadObj),
                        DeadlineGeneratedEvent.class
                );

                Set<ConstraintViolation<DeadlineGeneratedEvent>> violations = validator.validate(event);
                if (!violations.isEmpty()) {
                    log.warn("event=SLACK_PENDING_VALIDATION_FAILED recordId={} violations={}",
                            record.getId(),
                            violations.stream()
                                    .map(ConstraintViolation::getMessage)
                                    .toList()
                    );
                    acknowledge(record);
                    continue;
                }

                log.info("event=SLACK_PENDING_RETRY_STARTED recordId={} eventId={}",
                        record.getId(), event.getEventId());

                slackService.processDeadlineGenerated(event);

                acknowledge(record);

                log.info("event=SLACK_PENDING_ACK_COMPLETED recordId={}", record.getId());

            } catch (Exception e) {
                log.error("event=SLACK_PENDING_RETRY_FAILED recordId={}", record.getId(), e);
                acknowledge(record);
            }
        }
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        stringRedisTemplate.opsForStream().acknowledge(
                DeadlineStreamConstants.DEADLINE_GENERATED_STREAM,
                DeadlineStreamConstants.SLACK_SERVICE_GROUP,
                record.getId()
        );
    }
}
