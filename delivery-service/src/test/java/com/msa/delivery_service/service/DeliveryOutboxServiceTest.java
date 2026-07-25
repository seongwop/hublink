package com.msa.delivery_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.delivery_service.entity.DeliveryOutbox;
import com.msa.delivery_service.repository.DeliveryOutboxCommandRepository;
import com.msa.delivery_service.repository.DeliveryOutboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryOutboxServiceTest {

    @Mock
    private DeliveryOutboxRepository outboxRepository;

    @Mock
    private DeliveryOutboxCommandRepository outboxCommandRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private DeliveryPerformanceMetrics performanceMetrics;

    @InjectMocks
    private DeliveryOutboxService outboxService;

    @Test
    @DisplayName("Outbox 발행: Kafka ACK 성공 ID를 한 번에 상태 변경")
    void markPublishedOutboxesAtOnce() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        List<DeliveryOutbox> outboxes = List.of(
                createOutbox(firstId, "first"),
                createOutbox(secondId, "second")
        );
        CompletableFuture<SendResult<String, String>> completed =
                CompletableFuture.completedFuture(null);

        when(transactionTemplate.execute(any())).thenReturn(outboxes);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(completed);

        outboxService.publishPending();

        verify(outboxCommandRepository).markPublished(List.of(firstId, secondId));
        verify(outboxCommandRepository, never()).markFailed(any(), any());
    }

    @Test
    @DisplayName("Outbox 발행: Kafka ACK 성공 건만 일괄 변경하고 실패 건은 개별 변경")
    void markOnlySuccessfulOutboxesAsPublished() {
        UUID successId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();
        List<DeliveryOutbox> outboxes = List.of(
                createOutbox(successId, "success"),
                createOutbox(failedId, "failed")
        );
        CompletableFuture<SendResult<String, String>> completed =
                CompletableFuture.completedFuture(null);
        CompletableFuture<SendResult<String, String>> failed =
                CompletableFuture.failedFuture(new RuntimeException("send failed"));

        when(transactionTemplate.execute(any())).thenReturn(outboxes);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(completed, failed);

        outboxService.publishPending();

        verify(outboxCommandRepository).markPublished(List.of(successId));
        verify(outboxCommandRepository).markFailed(eq(failedId), contains("send failed"));
    }

    private DeliveryOutbox createOutbox(UUID outboxId, String eventKey) {
        DeliveryOutbox outbox = DeliveryOutbox.create(
                "delivery.create.succeed",
                eventKey,
                "{}"
        );
        ReflectionTestUtils.setField(outbox, "outboxId", outboxId);
        return outbox;
    }
}
