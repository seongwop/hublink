package com.msa.delivery_service.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.core_common.error.exception.CustomException;
import com.msa.core_common.error.exception.ErrorCode;
import com.msa.delivery_service.dto.DeliveryRequest;
import com.msa.delivery_service.dto.DeliveryResponse;
import com.msa.delivery_service.enums.DeliveryErrorCode;
import com.msa.delivery_service.service.DeliveryOutboxService;
import com.msa.delivery_service.service.DeliveryService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryCreateKafkaConsumer {

    private static final String CREATE_TOPIC = "delivery.create";
    private static final String CREATE_SUCCEED_TOPIC = "delivery.create.succeed";
    private static final String CREATE_FAILED_TOPIC = "delivery.create.failed";
    private static final String CREATE_DLQ_TOPIC = "delivery.create.dlq";

    private final DeliveryService deliveryService;
    private final DeliveryOutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    // delivery.create 토픽에서 문자열 payload를 받아 배송 생성
    // String payload + ObjectMapper 방식으로 DTO 변환
    @KafkaListener(
            topics = CREATE_TOPIC,
            groupId = "${spring.kafka.consumer.group-id:delivery-service}"
    )
    public void consume(ConsumerRecord<String, String> record) {
        String payload = record.value();

        // 메세지 키는 orderId 우선, 없으면 record key, 둘 다 없으면 offset 사용
        String messageKey = resolveMessageKey(record, null);
        DeliveryRequest request = null;

        try {
            // DeliveryRequest DTO 역직렬화 및 검증
            request = objectMapper.readValue(payload, DeliveryRequest.class);
            validate(request);
            messageKey = resolveMessageKey(record, request.getOrderId());

            // 멱등성 보장을 위해 이미 존재하면 기존 결과 반환
            var existingDelivery = deliveryService.findDeliveryByOrderId(request.getOrderId());
            DeliveryResponse response;
            if (existingDelivery.isPresent()) {
                response = existingDelivery.get();
                publishSuccess(messageKey, response);
            } else {
                response = deliveryService.createDelivery(request);
            }

            log.info("배송 생성 이벤트를 정상 처리했습니다. orderId={}, deliveryId={}",
                    request.getOrderId(),
                    response.getDeliveryId()
            );
        } catch (CustomException e) {
            if (request != null && request.getOrderId() != null) {
                messageKey = request.getOrderId().toString();
            }

            // 중복 배송 생성은 무시 처리
            if (e.getErrorCode() == DeliveryErrorCode.DUPLICATE_ORDER_DELIVERY) {
                log.info("중복 배송 생성 이벤트 무시. key={}, offset={}",
                        messageKey,
                        record.offset()
                );
                return;
            }

            // 실패 시 키에 오프셋을 임시로 넣고 요청 데이터를 그대로 전달
            if (messageKey == null) {
                messageKey = "offset-" + record.offset();
            }
            log.error("배송 생성 이벤트 처리 실패. key={}, offset={}", messageKey, record.offset(), e);

            // orderId가 존재할 경우만 보상 트랜잭션을 위해 failed 처리
            if (request != null && request.getOrderId() != null) {
                publishFailed(request.getOrderId().toString(), payload);
            } else {
                publishDlq(messageKey, record, payload, e);
            }
        } catch (Exception e) {
            // 실패 시 키에 오프셋을 임시로 넣고 요청 데이터를 그대로 전달
            if (messageKey == null) {
                messageKey = "offset-" + record.offset();
            }
            log.error(
                    "배송 생성 이벤트 처리에 실패했습니다. key={}, offset={}",
                    messageKey,
                    record.offset(),
                    e
            );
            if (request != null && request.getOrderId() != null) {
                publishFailed(request.getOrderId().toString(), payload);
            }
            publishDlq(messageKey, record, payload, e);
        }
    }

    private void validate(DeliveryRequest request) {
        // 검증 실패도 실패 토픽으로 보내기 위해 예외 발생
        Set<ConstraintViolation<DeliveryRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    private void publishSuccess(String key, DeliveryResponse response) {
        outboxService.enqueue(CREATE_SUCCEED_TOPIC, key, response);
    }

    private void publishFailed(String key, String originalPayload) {
        outboxService.enqueueSerialized(CREATE_FAILED_TOPIC, key, originalPayload);
    }

    private void publishDlq(String key, ConsumerRecord<String, String> record, String originalPayload, Exception e) {
        outboxService.enqueue(CREATE_DLQ_TOPIC, key, new DeliveryCreateDlqEvent(
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
                failureReason(e),
                originalPayload
        ));
    }

    // DLQ에 이벤트를 발행할 때 넣을 실패 상세 원인
    private String failureReason(Exception e) {
        if (e instanceof CustomException customException) {
            ErrorCode errorCode = customException.getErrorCode();
            return errorCode.getCode() + ":" + errorCode.getMessage();
        }

        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : e.getClass().getSimpleName() + ":" + message;
    }

    private String resolveMessageKey(ConsumerRecord<String, String> record, UUID orderId) {
        if (orderId != null) {
            return orderId.toString();
        }
        if (record.key() != null && !record.key().isBlank()) {
            return record.key();
        }
        return "offset-" + record.offset();
    }

    // DLQ 이벤트 형식
    private record DeliveryCreateDlqEvent(
            String topic,
            int partition,
            long offset,
            String key,
            String reason,
            String originalPayload
    ) {
    }
}
