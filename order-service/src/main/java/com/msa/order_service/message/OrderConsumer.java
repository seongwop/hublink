package com.msa.order_service.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.core_common.error.exception.CustomException;
import com.msa.order_service.dto.res.StockResultDto;
import com.msa.order_service.error.OrderErrorCode;
import com.msa.order_service.service.OrderService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

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
            log.error("event=ORDER_STOCK_SUCCESS_HANDLE_FAILED", e);
            throw new CustomException(OrderErrorCode.FAIL_STOCK);
        }
    }

    @KafkaListener(topics = "stock.decrease.failed", groupId = "order-group")
    @RetryableTopic(attempts = "5", backoff = @Backoff(delay = 1000, multiplier = 2))
    public void handleStockFailed(String jsonMessage, Acknowledgment ack) {
        try {
            UUID orderId = UUID.fromString(objectMapper.readTree(jsonMessage).get("orderId").asText());
            log.warn("event=ORDER_STOCK_FAILURE_CONSUMED orderId={}", orderId);

            orderService.processStockFailed(orderId);

            ack.acknowledge();
        } catch (Exception e) {
            log.error("event=ORDER_STOCK_FAILURE_HANDLE_FAILED", e);
            throw new CustomException(OrderErrorCode.FAIL_STOCK);
        }
    }

    @KafkaListener(topics = "delivery.create.succeed", groupId = "order-group")
    @RetryableTopic(attempts = "5", backoff = @Backoff(delay = 1000, multiplier = 2))
    public void handleDeliverySuccess(String jsonMessage, Acknowledgment ack) {
        try {
            UUID orderId = UUID.fromString(objectMapper.readTree(jsonMessage).get("orderId").asText());
            log.info("event=ORDER_DELIVERY_SUCCESS_CONSUMED orderId={}", orderId);

            orderService.processDeliverySuccess(orderId);

            ack.acknowledge();
        } catch (Exception e) {
            log.error("event=ORDER_DELIVERY_SUCCESS_HANDLE_FAILED", e);
            throw new CustomException(OrderErrorCode.FAIL_DELIVERY);
        }
    }

    @KafkaListener(topics = "delivery.create.failed", groupId = "order-group")
    @RetryableTopic(attempts = "5", backoff = @Backoff(delay = 1000, multiplier = 2))
    public void handleDeliveryFailed(String jsonMessage, Acknowledgment ack) {
        try {
            UUID orderId = UUID.fromString(objectMapper.readTree(jsonMessage).get("orderId").asText());
            log.error("event=ORDER_DELIVERY_FAILURE_CONSUMED orderId={}", orderId);

            orderService.processDeliveryFailed(orderId);

            ack.acknowledge();
        } catch (Exception e) {
            log.error("event=ORDER_DELIVERY_FAILURE_HANDLE_FAILED", e);
            throw new CustomException(OrderErrorCode.FAIL_DELIVERY);
        }
    }
}
