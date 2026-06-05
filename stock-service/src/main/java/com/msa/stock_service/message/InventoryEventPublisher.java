package com.msa.stock_service.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 재고 서비스가 결과 편지를 Kafka로 보내는 발행기.
 * - stock.decrease.success / stock.decrease.failed
 * - stock.increase.success
 * payload만 JSON으로 직렬화해 발행한다.
 * orderId는 Kafka 메시지 key로 전달한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 결과 편지 발행.
     *
     * @param topic   발행할 토픽 (예: "stock.decrease.success")
     * @param orderId 주문 ID. Kafka key로 사용.
     * @param payload 실제 보낼 데이터. JSON으로 직렬화된다.
     */
    public void publish(String topic, UUID orderId, Object payload) {
        try {
            String message = objectMapper.writeValueAsString(payload);
            kafkaTemplate.send(topic, orderId.toString(), message);
            log.info("event=STOCK_EVENT_PUBLISHED topic={} orderId={}", topic, orderId);
        } catch (Exception e) {
            log.error("event=STOCK_EVENT_PUBLISH_FAILED topic={} orderId={}", topic, orderId, e);
            throw new RuntimeException("이벤트 발행 실패", e);
        }
    }
}
