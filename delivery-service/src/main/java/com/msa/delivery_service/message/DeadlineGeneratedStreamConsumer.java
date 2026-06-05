package com.msa.delivery_service.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.core_common.stream.DeadlineStreamConstants;
import com.msa.delivery_service.service.DeliveryService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeadlineGeneratedStreamConsumer implements StreamListener<String, MapRecord<String, String, String>> {
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final DeliveryService deliveryService;

    // 스트림으로부터 메세지가 들어오면 호출
    @Override
    public void onMessage(MapRecord<String, String, String> record) {
        try {
            process(record);
            acknowledge(record.getId());
            log.info("event=DELIVERY_DEADLINE_STREAM_ACKED stream={} group={} recordId={}",
                    DeadlineStreamConstants.DEADLINE_GENERATED_STREAM,
                    DeadlineStreamConstants.DELIVERY_SERVICE_GROUP,
                    record.getId()
            );
        } catch (Exception e) {
            log.error("event=DELIVERY_DEADLINE_PROCESSING_FAILED recordId={}", record.getId(), e);
        }
    }

    // 메세지를 역직렬화하고 배송 최종시한 반영
    void process(MapRecord<String, ?, ?> record) throws Exception {
        Object payloadObj = record.getValue().get("payload");
        if (payloadObj == null) {
            log.warn("event=DELIVERY_DEADLINE_PAYLOAD_MISSING recordId={}", record.getId());
            return;
        }

        DeadlineGeneratedEvent event = objectMapper.readValue(
                String.valueOf(payloadObj),
                DeadlineGeneratedEvent.class
        );
        // 데이터 검증
        Set<ConstraintViolation<DeadlineGeneratedEvent>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            log.warn("event=DELIVERY_DEADLINE_VALIDATION_FAILED recordId={} violations={}",
                    record.getId(),
                    violations.stream()
                            .map(ConstraintViolation::getMessage)
                            .toList()
            );
            return;
        }

        deliveryService.updateFinalDepartureDeadline(event);
        log.info("event=DELIVERY_DEADLINE_EVENT_PROCESSED recordId={} eventId={} deliveryId={} aiMessageId={}",
                record.getId(),
                event.getEventId(),
                event.getDeliveryId(),
                event.getAiMessageId()
        );
        log.info("event=DELIVERY_DEADLINE_APPLIED deliveryId={} eventId={}",
                event.getDeliveryId(),
                event.getEventId()
        );
    }

    private void acknowledge(RecordId recordId) {
        stringRedisTemplate.opsForStream().acknowledge(
                DeadlineStreamConstants.DEADLINE_GENERATED_STREAM,
                DeadlineStreamConstants.DELIVERY_SERVICE_GROUP,
                recordId
        );
    }
}
