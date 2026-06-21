package com.msa.delivery_service.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.delivery_service.dto.DeliveryRequest;
import com.msa.delivery_service.dto.DeliveryResponse;
import com.msa.delivery_service.enums.DeliveryStatus;
import com.msa.delivery_service.service.DeliveryOutboxService;
import com.msa.delivery_service.service.DeliveryService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryCreateKafkaConsumerTest {

    private static final String CREATE_SUCCEED_TOPIC = "delivery.create.succeed";
    private static final String CREATE_FAILED_TOPIC = "delivery.create.failed";
    private static final String CREATE_DLQ_TOPIC = "delivery.create.dlq";

    @Mock
    private DeliveryService deliveryService;

    @Mock
    private DeliveryOutboxService outboxService;

    @Mock
    private MeterRegistry meterRegistry;

    private ObjectMapper objectMapper;
    private DeliveryCreateKafkaConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        consumer = new DeliveryCreateKafkaConsumer(
                deliveryService,
                outboxService,
                objectMapper,
                validator,
                meterRegistry
        );
    }

    @Test
    @DisplayName("배송 생성 Kafka: 성공 시 succeed outbox 저장")
    void enqueueSucceedTopic() throws Exception {
        // given
        UUID orderId = UUID.randomUUID();
        DeliveryRequest request = createDeliveryRequest(orderId);
        DeliveryResponse response = DeliveryResponse.builder()
                .deliveryId(UUID.randomUUID())
                .orderId(orderId)
                .status(DeliveryStatus.PENDING)
                .build();
        String payload = objectMapper.writeValueAsString(request);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("delivery.create", 0, 12L, null, payload);

        when(deliveryService.findDeliveryByOrderId(orderId)).thenReturn(Optional.empty());
        when(deliveryService.createDelivery(any(DeliveryRequest.class))).thenReturn(response);

        // when
        consumer.consume(record);

        // then
        verify(deliveryService).createDelivery(any(DeliveryRequest.class));
        verify(outboxService, never()).enqueue(eq(CREATE_SUCCEED_TOPIC), any(), any());
    }

    @Test
    @DisplayName("배송 생성 Kafka: 중복 이벤트면 기존 배송 결과 재사용")
    void enqueueExistingDeliveryForDuplicateEvent() throws Exception {
        // given
        UUID orderId = UUID.randomUUID();
        DeliveryRequest request = createDeliveryRequest(orderId);
        DeliveryResponse response = DeliveryResponse.builder()
                .deliveryId(UUID.randomUUID())
                .orderId(orderId)
                .status(DeliveryStatus.PENDING)
                .build();
        String payload = objectMapper.writeValueAsString(request);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("delivery.create", 0, 13L, null, payload);

        when(deliveryService.findDeliveryByOrderId(orderId)).thenReturn(Optional.of(response));

        // when
        consumer.consume(record);

        // then
        // createDelivery는 재호출 X
        verify(deliveryService, never()).createDelivery(any(DeliveryRequest.class));
        // 기존 delivery 결과가 성공 이벤트로 outbox에 저장되는지 검증
        verify(outboxService).enqueue(CREATE_SUCCEED_TOPIC, orderId.toString(), response);
    }

    @Test
    @DisplayName("배송 생성 Kafka: 처리 실패 시 failed 와 DLQ outbox 저장")
    void enqueueFailedAndDlqTopic() throws Exception {
        // given
        UUID orderId = UUID.randomUUID();
        DeliveryRequest request = createDeliveryRequest(orderId);
        String payload = objectMapper.writeValueAsString(request);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("delivery.create", 0, 27L, null, payload);

        when(deliveryService.findDeliveryByOrderId(orderId)).thenReturn(Optional.empty());
        when(deliveryService.createDelivery(any(DeliveryRequest.class)))
                .thenThrow(new RuntimeException("create failed"));

        // when
        consumer.consume(record);

        // then
        // 원본 payload는 failed topic용 outbox에 저장
        verify(outboxService).enqueueSerialized(CREATE_FAILED_TOPIC, orderId.toString(), payload);
        // Kafka 메타데이터 + 실패 사유는 DLQ outbox에 저장
        verify(outboxService).enqueue(eq(CREATE_DLQ_TOPIC), eq(orderId.toString()), any());
    }

    @Test
    @DisplayName("배송 생성 Kafka: 잘못된 JSON이면 failed 없이 DLQ 에만 저장")
    void enqueueOnlyDlqWhenOrderIdIsUnavailable() {
        // given
        String invalidPayload = "{invalid-json}";
        ConsumerRecord<String, String> record = new ConsumerRecord<>("delivery.create", 0, 31L, null, invalidPayload);

        // when
        consumer.consume(record);

        // then
        // orderId를 추출할 수 없으면 failed topic 발행 X
        verify(outboxService, never()).enqueueSerialized(eq(CREATE_FAILED_TOPIC), any(), any());
        // offset 기반 임시 key로 DLQ만 저장
        verify(outboxService).enqueue(eq(CREATE_DLQ_TOPIC), eq("offset-31"), any());
    }

    private DeliveryRequest createDeliveryRequest(UUID orderId) {
        DeliveryRequest request = instantiate(DeliveryRequest.class);
        ReflectionTestUtils.setField(request, "orderId", orderId);
        ReflectionTestUtils.setField(request, "ordererName", "orderer");
        ReflectionTestUtils.setField(request, "ordererEmail", "orderer@hublink.com");
        ReflectionTestUtils.setField(request, "orderedAt", LocalDateTime.now());
        ReflectionTestUtils.setField(request, "requestMessage", "deliver fast");
        ReflectionTestUtils.setField(request, "products", List.of(createProduct()));
        ReflectionTestUtils.setField(request, "supplyCompanyId", UUID.randomUUID());
        ReflectionTestUtils.setField(request, "receiverCompanyId", UUID.randomUUID());
        ReflectionTestUtils.setField(request, "deliveryAddress", "Seoul");
        ReflectionTestUtils.setField(request, "receiverName", "receiver");
        return request;
    }

    private DeliveryRequest.Product createProduct() {
        DeliveryRequest.Product product = instantiate(DeliveryRequest.Product.class);
        ReflectionTestUtils.setField(product, "productName", "keyboard");
        ReflectionTestUtils.setField(product, "quantity", 1);
        return product;
    }

    private <T> T instantiate(Class<T> type) {
        try {
            Constructor<T> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
