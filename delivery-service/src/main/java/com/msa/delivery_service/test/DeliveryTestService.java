package com.msa.delivery_service.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.core_common.stream.DeadlineStreamConstants;
import com.msa.delivery_service.dto.DeliveryRequest;
import com.msa.delivery_service.message.DeadlineRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryTestService {

    private static final String DELIVERY_CREATE_TOPIC = "delivery.create";
    private static final long KAFKA_SEND_TIMEOUT_SECONDS = 5;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public DeliveryTestKafkaPublishResponse publishDeliveryCreateEvent(
            DeliveryRequest request
    ) {
        // 주문/재고 흐름 우회용 Kafka key 생성
        String eventKey = request.getOrderId().toString();

        // consumer 입력과 동일한 DeliveryRequest JSON 생성
        String payload = toPayload(request);

        try {
            // Kafka broker ack 대기
            kafkaTemplate
                    .send(DELIVERY_CREATE_TOPIC, eventKey, payload)
                    .get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            log.info("event=DELIVERY_TEST_CREATE_EVENT_PUBLISHED topic={} eventKey={} orderId={}",
                    DELIVERY_CREATE_TOPIC,
                    eventKey,
                    request.getOrderId()
            );

            return DeliveryTestKafkaPublishResponse.builder()
                    .topic(DELIVERY_CREATE_TOPIC)
                    .eventKey(eventKey)
                    .orderId(request.getOrderId())
                    .message("delivery.create event published")
                    .build();
        } catch (Exception e) {
            log.error("event=DELIVERY_TEST_CREATE_EVENT_PUBLISH_FAILED topic={} eventKey={} orderId={}",
                    DELIVERY_CREATE_TOPIC,
                    eventKey,
                    request.getOrderId(),
                    e
            );

            throw new IllegalStateException("delivery.create 이벤트 발행 실패", e);
        }
    }

    public DeliveryTestStreamPublishResponse publishDeadlineRequestedEvent(
            DeadlineRequestedEvent event
    ) {
        String payload = toPayload(event);

        RecordId recordId = stringRedisTemplate.opsForStream().add(
                MapRecord.create(
                        DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM,
                        Map.of("payload", payload)
                )
        );

        log.info("event=DELIVERY_TEST_DEADLINE_REQUESTED_EVENT_PUBLISHED stream={} recordId={} eventId={} deliveryId={} orderId={}",
                DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM,
                recordId,
                event.getEventId(),
                event.getDeliveryId(),
                event.getOrderId()
        );

        return DeliveryTestStreamPublishResponse.builder()
                .streamKey(DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM)
                .recordId(recordId == null ? null : recordId.getValue())
                .eventId(event.getEventId())
                .deliveryId(event.getDeliveryId())
                .orderId(event.getOrderId())
                .message("DeadlineNotificationRequestedEvent published")
                .build();
    }

    private String toPayload(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.error("event=DELIVERY_TEST_PAYLOAD_CONVERT_FAILED payloadType={}",
                    event == null ? null : event.getClass().getSimpleName(), e);

            throw new IllegalStateException("테스트 이벤트 직렬화 실패", e);
        }
    }
}
