package com.msa.order_service.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.core_common.error.exception.CustomException;
import com.msa.order_service.dto.res.StockResultDto;
import com.msa.order_service.error.OrderErrorCode;
import com.msa.order_service.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderConsumer {

    private final OrderService orderService; // 🟢 리포지토리 제거, 서비스만 의존!
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "stock.decrease.success", groupId = "order-group")
    @RetryableTopic(attempts = "5", backoff = @Backoff(delay = 1000, multiplier = 2))
    public void handleStockSuccess(String jsonMessage, Acknowledgment ack) {
        try {
            StockResultDto result = objectMapper.readValue(jsonMessage, StockResultDto.class);

            orderService.processStockSuccess(result);

            ack.acknowledge();
        } catch (Exception e) {
            log.error("재고 성공 처리 예외 발생 - 자동 롤백 및 재배달 유도", e);
            throw new CustomException(OrderErrorCode.FAIL_STOCK);
        }
    }

    @KafkaListener(topics = "stock.decrease.failed", groupId = "order-group")
    @RetryableTopic(attempts = "5", backoff = @Backoff(delay = 1000, multiplier = 2))
    public void handleStockFailed(String jsonMessage, Acknowledgment ack) {
        try {
            UUID orderId = UUID.fromString(objectMapper.readTree(jsonMessage).get("orderId").asText());
            log.warn("재고 부족으로 인한 주문 취소 공정 가동. 주문 ID: {}", orderId);

            orderService.processStockFailed(orderId);

            ack.acknowledge();
        } catch (Exception e) {
            log.error("재고 실패 처리 예외 발생", e);
            throw new CustomException(OrderErrorCode.FAIL_STOCK);
        }
    }

    @KafkaListener(topics = "delivery.create.succeed", groupId = "order-group")
    @RetryableTopic(attempts = "5", backoff = @Backoff(delay = 1000, multiplier = 2))
    public void handleDeliverySuccess(String jsonMessage, Acknowledgment ack) {
        try {
            UUID orderId = UUID.fromString(objectMapper.readTree(jsonMessage).get("orderId").asText());
            log.info("주문 최종 확정. 주문 ID: {}", orderId);

            orderService.processDeliverySuccess(orderId);

            ack.acknowledge();
        } catch (Exception e) {
            log.error("배송 완료 처리 예외 발생", e);
            throw new CustomException(OrderErrorCode.FAIL_DELIVERY);
        }
    }

    @KafkaListener(topics = "delivery.create.failed", groupId = "order-group")
    @RetryableTopic(attempts = "5", backoff = @Backoff(delay = 1000, multiplier = 2))
    public void handleDeliveryFailed(String jsonMessage, Acknowledgment ack) {
        try {
            UUID orderId = UUID.fromString(objectMapper.readTree(jsonMessage).get("orderId").asText());
            log.error("배송 생성 실패 수령 -> 보상 트랜잭션 발동. 주문 ID: {}", orderId);

            orderService.processDeliveryFailed(orderId);

            ack.acknowledge();
        } catch (Exception e) {
            log.error("배송 실패 대응 중 시스템 예외 발생", e);
            throw new CustomException(OrderErrorCode.FAIL_DELIVERY);
        }
    }
}
