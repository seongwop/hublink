package com.msa.delivery_service.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.core_common.stream.DeadlineStreamConstants;
import com.msa.delivery_service.service.DeliveryService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeadlineGeneratedStreamConsumerTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private StreamOperations<String, Object, Object> streamOperations;

    @Mock
    private DeliveryService deliveryService;

    @Mock
    private DeadlineGeneratedStreamConsumer retryStreamConsumer;

    private DeadlineGeneratedStreamConsumer streamConsumer;
    private DeadlineGeneratedPendingRetryConsumer pendingRetryConsumer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        streamConsumer = new DeadlineGeneratedStreamConsumer(
                stringRedisTemplate,
                objectMapper,
                validator,
                deliveryService
        );
        pendingRetryConsumer = new DeadlineGeneratedPendingRetryConsumer(
                stringRedisTemplate,
                retryStreamConsumer
        );
    }

    @Test
    @DisplayName("스트림 소비: 정상 payload 배송 마감시간 갱신과 ack 검증")
    void consumeValidPayload() throws Exception {
        // given
        UUID deliveryId = UUID.randomUUID();
        LocalDateTime deadline = LocalDateTime.of(2026, 5, 25, 9, 0);
        DeadlineGeneratedEvent event = new DeadlineGeneratedEvent(
                UUID.randomUUID(),
                deliveryId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "U123456",
                deadline,
                "DELIVERY_DEADLINE",
                "message"
        );
        MapRecord<String, String, String> record = streamRecord(objectMapper.writeValueAsString(event));
        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);

        // when
        streamConsumer.onMessage(record);

        // then

        // 배송 마감시간 갱신 검증
        ArgumentCaptor<DeadlineGeneratedEvent> eventCaptor = ArgumentCaptor.forClass(DeadlineGeneratedEvent.class);
        verify(deliveryService).updateFinalDepartureDeadline(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getDeliveryId()).isEqualTo(deliveryId);
        assertThat(eventCaptor.getValue().getFinalDepartureDeadline()).isEqualTo(deadline);

        // ack 검증
        verifyAck(record.getId());
    }

    @Test
    @DisplayName("스트림 소비: 잘못된 payload ack 검증")
    void ackInvalidPayload() throws Exception {
        // given
        String invalidPayload = objectMapper.writeValueAsString(Map.of(
                "eventId", UUID.randomUUID(),
                "message", "missing required fields"
        ));
        MapRecord<String, String, String> record = streamRecord(invalidPayload);
        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);

        // when
        streamConsumer.onMessage(record);

        // then

        // 잘못된 payload 건너뜀 검증
        verify(deliveryService, never()).updateFinalDepartureDeadline(any());

        // ack 검증
        verifyAck(record.getId());
    }

    @Test
    @DisplayName("pending 재처리: 대상 레코드 재처리와 ack 검증")
    void retryPendingRecord() throws Exception {
        // given
        MapRecord<String, Object, Object> record = pendingRecord("1-0");
        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        whenPending(pending("1-0", 1));
        whenClaim(List.of(record));

        // when
        pendingRetryConsumer.retryPendingMessages();

        // then

        // 대상 레코드 재처리 검증
        verify(retryStreamConsumer).process(record);

        // ack 검증
        verifyAck(record.getId());
    }

    @Test
    @DisplayName("pending 재처리: 임계치 초과 메시지 DLQ 전송")
    void moveExceededPendingToDlq() throws Exception {
        // given
        MapRecord<String, Object, Object> record = pendingRecord("1-0");
        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        whenPending(pending("1-0", 4));
        whenClaim(List.of(record));
        doThrow(new RuntimeException("retry failed"))
                .when(retryStreamConsumer)
                .process(record);

        // when
        pendingRetryConsumer.retryPendingMessages();

        // then

        // 재처리 제외 검증
        verify(retryStreamConsumer).process(record);

        // DLQ 전송 검증
        verify(streamOperations).add(
                eq(DeadlineStreamConstants.DEADLINE_GENERATED_DELIVERY_DLQ_STREAM),
                any(Map.class)
        );

        // ack 검증
        verifyAck(record.getId());
    }

    @Test
    @DisplayName("pending 재처리: 최근 전달 메시지 건너뜀")
    void skipRecentPendingRecord() throws Exception {
        // given
        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        whenPending(pending("1-0", Duration.ofSeconds(30), 1));

        // when
        pendingRetryConsumer.retryPendingMessages();

        // then

        // 최근 전달 메시지 건너뜀 검증
        verify(retryStreamConsumer, never()).process(any());
        verify(streamOperations, never()).acknowledge(
                any(String.class),
                any(String.class),
                any(RecordId.class)
        );
    }

    private void whenPending(PendingMessage pendingMessage) {
        when(streamOperations.pending(
                eq(DeadlineStreamConstants.DEADLINE_GENERATED_STREAM),
                any(Consumer.class),
                any(Range.class),
                eq(100L)
        )).thenReturn(new PendingMessages(DeadlineStreamConstants.DELIVERY_SERVICE_GROUP, List.of(pendingMessage)));
    }

    private void whenClaim(List<MapRecord<String, Object, Object>> records) {
        when(streamOperations.claim(
                eq(DeadlineStreamConstants.DEADLINE_GENERATED_STREAM),
                eq(DeadlineStreamConstants.DELIVERY_SERVICE_GROUP),
                eq(DeadlineStreamConstants.DELIVERY_SERVICE_CONSUMER),
                eq(Duration.ofMinutes(1)),
                any(RecordId.class)
        )).thenReturn(records);
    }

    private void verifyAck(RecordId recordId) {
        verify(streamOperations).acknowledge(
                eq(DeadlineStreamConstants.DEADLINE_GENERATED_STREAM),
                eq(DeadlineStreamConstants.DELIVERY_SERVICE_GROUP),
                eq(recordId)
        );
    }

    private PendingMessage pending(String recordId, long deliveryCount) {
        return pending(recordId, Duration.ofMinutes(2), deliveryCount);
    }

    private PendingMessage pending(String recordId, Duration idleTime, long deliveryCount) {
        return new PendingMessage(
                RecordId.of(recordId),
                Consumer.from(
                        DeadlineStreamConstants.DELIVERY_SERVICE_GROUP,
                        DeadlineStreamConstants.DELIVERY_SERVICE_CONSUMER
                ),
                idleTime,
                deliveryCount
        );
    }

    private MapRecord<String, String, String> streamRecord(String payload) {
        return MapRecord
                .create(DeadlineStreamConstants.DEADLINE_GENERATED_STREAM, Map.of("payload", payload))
                .withId(RecordId.of("1-0"));
    }

    private MapRecord<String, Object, Object> pendingRecord(String recordId) {
        return MapRecord
                .create(DeadlineStreamConstants.DEADLINE_GENERATED_STREAM, Map.<Object, Object>of("payload", "{}"))
                .withId(RecordId.of(recordId));
    }
}
