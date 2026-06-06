package com.msa.delivery_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.core_common.stream.DeadlineStreamConstants;
import com.msa.delivery_service.dto.DeliveryTestStreamPublishResponse;
import com.msa.delivery_service.message.DeadlineRequestedEvent;
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
public class DeliveryTestService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

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

    private String toPayload(DeadlineRequestedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.error("event=DELIVERY_TEST_DEADLINE_REQUESTED_PAYLOAD_CONVERT_FAILED eventId={}",
                    event == null ? null : event.getEventId(),
                    e
            );

            throw new IllegalStateException("AI 시한 생성 요청 이벤트 직렬화에 실패했습니다.", e);
        }
    }
}