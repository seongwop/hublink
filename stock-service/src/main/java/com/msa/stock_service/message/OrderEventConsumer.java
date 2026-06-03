package com.msa.stock_service.message;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.core_common.error.exception.CustomException;
import com.msa.stock_service.dto.StockDecreaseCommandDto;
import com.msa.stock_service.dto.StockHistoryResponseDto;
import com.msa.stock_service.dto.StockItemCommandDto;
import com.msa.stock_service.service.StockOrchestrator;
import com.msa.stock_service.service.StockService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * order-service가 보낸 재고 명령 편지를 받아 처리한다.
 *
 *  - stock.decrease : 재고 차감 명령 → 차감 후 결과 발행
 *  - stock.increase : 재고 복원 명령(보상) → 복원 후 완료 발행
 *
 * 봉투 없이 payload만 주고받는다. orderId는 Kafka 메시지 key로 받는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventConsumer {

    private final StockOrchestrator stockOrchestrator;     // 재고 차감 로직
    private final StockService stockService;               // 재고 복원 로직
    private final InventoryEventPublisher eventPublisher;  // 결과 발행기
    private final ObjectMapper objectMapper;

    /**
     * 재고 차감 명령 수신.
     * payload: StockDecreaseCommandDto를 직렬화한 JSON 객체.
     */
    @KafkaListener(
            topics = "stock.decrease",
            groupId = "inventory-service.stock-decrease.handler"
    )
    public void onStockDecrease(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_KEY) String key
    ) {
        UUID orderId = UUID.fromString(key);
        log.info("[재고 차감 요청 수신] orderId={}", orderId);

        try {
            StockDecreaseCommandDto command = objectMapper.readValue(message, StockDecreaseCommandDto.class);
            List<StockItemCommandDto> request = toDecreaseRequests(command);

            // 기존 재고 차감 로직 그대로 호출
            List<StockHistoryResponseDto> result = stockOrchestrator.decreaseStock(request);

            eventPublisher.publish("stock.decrease.success", orderId, toStockResult(orderId, result));
            log.info("[재고 차감 성공] orderId={}", orderId);
        } catch (IllegalArgumentException e) {
            // 재고 부족·상품 없음 등 예상된 실패 → 실패 편지 발행
            log.warn("[재고 차감 실패] orderId={}, 사유={}", orderId, e.getMessage());
            eventPublisher.publish(
                    "stock.decrease.failed",
                    orderId,
                    toStockFailure(orderId, e.getMessage())
            );
        } catch (CustomException e) {
            log.warn("[?ш퀬 李④컧 ?ㅽ뙣] orderId={}, ?ъ쑀={}", orderId, e.getMessage());
            eventPublisher.publish(
                    "stock.decrease.failed",
                    orderId,
                    toStockFailure(orderId, e.getMessage())
            );
        } catch (Exception e) {
            // JSON 파싱 오류 등 예상 못 한 실패 → 다시 던져 Kafka가 재시도하게 한다
            log.error("[재고 차감 처리 중 오류] orderId={}", orderId, e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 재고 복원 명령 수신 (배송 실패 등 보상 흐름).
     * payload: List<StockItemCommandDto>를 직렬화한 JSON 배열.
     */
    @KafkaListener(
            topics = "stock.increase",
            groupId = "inventory-service.stock-increase.handler"
    )
    public void onStockIncrease(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_KEY) String key
    ) {
        UUID orderId = UUID.fromString(key);
        log.info("[재고 복원 요청 수신] orderId={}", orderId);

        try {
            List<StockItemCommandDto> request = objectMapper.readValue(
                    message,
                    new TypeReference<List<StockItemCommandDto>>() {
                    }
            );

            // 기존 재고 복원 로직 그대로 호출
            stockService.restoreStock(request);

            // 완료 알림 발행 (payload는 orderId만 담은 단순 맵)
            eventPublisher.publish(
                    "stock.increase.success",
                    orderId,
                    Map.of("orderId", orderId.toString())
            );
            log.info("[재고 복원 완료] orderId={}", orderId);
        } catch (Exception e) {
            log.error("[재고 복원 처리 중 오류] orderId={}", orderId, e);
            throw new RuntimeException(e);
        }
    }

    private Map<String, Object> toStockResult(UUID orderId, List<StockHistoryResponseDto> result) {
        StockHistoryResponseDto first = result.isEmpty() ? null : result.get(0);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("products", result.stream()
                .map(this::toProductPayload)
                .collect(Collectors.toList()));
        payload.put("ordererName", first == null ? "" : first.getOrderName());
        payload.put("ordererEmail", first == null ? "" : first.getOrderEmail());
        payload.put("deliveryAddress", first == null ? "" : first.getDeliveryAddress());
        payload.put("receiverCompanyName", first == null ? "" : first.getReceiverCompanyName());

        return payload;
    }

    private Map<String, Object> toProductPayload(StockHistoryResponseDto item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productId", item.getProductId());
        payload.put("isSuccess", item.isSuccess());
        payload.put("failureReason", "");
        payload.put("name", item.getName());
        payload.put("price", item.getPrice());
        payload.put("hubId", item.getHubId());

        return payload;
    }

    private List<StockItemCommandDto> toDecreaseRequests(StockDecreaseCommandDto command) {
        if (command.getItems() == null) {
            return List.of();
        }

        return command.getItems().stream()
                .map(item -> new StockItemCommandDto(
                        item.getProductId(),
                        item.getQuantity(),
                        command.getOrderId(),
                        command.getOrdererName(),
                        command.getOrdererEmail(),
                        command.getDeliveryAddress(),
                        command.getReceiverCompanyName()
                ))
                .toList();
    }

    private Map<String, Object> toStockFailure(UUID orderId, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId.toString());
        payload.put("reason", reason != null ? reason : "Unknown Error");

        return payload;
    }
}
