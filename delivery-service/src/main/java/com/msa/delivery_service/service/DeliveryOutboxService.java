package com.msa.delivery_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.delivery_service.entity.DeliveryOutbox;
import com.msa.delivery_service.repository.DeliveryOutboxCommandRepository;
import com.msa.delivery_service.repository.DeliveryOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryOutboxService {

    private static final int MAX_RETRY_COUNT = 5;
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final DeliveryOutboxRepository outboxRepository;
    private final DeliveryOutboxCommandRepository outboxCommandRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    // Outbox 처리 시간 계측
    private final DeliveryPerformanceMetrics performanceMetrics;

    @Transactional
    public void enqueue(String topic, String eventKey, Object payload) {
        String serializedPayload;
        long serializeStartNanos = performanceMetrics.start();
        try {
            serializedPayload = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Kafka outbox payload 직렬화에 실패했습니다.", e);
        } finally {
            performanceMetrics.recordOutboxStage("serialize_payload", serializeStartNanos);
        }
        enqueueSerialized(topic, eventKey, serializedPayload);
    }

    @Transactional
    public void enqueueSerialized(String topic, String eventKey, String serializedPayload) {
        // Outbox 중복 제어 insert 처리 시간 계측
        UUID outboxId = performanceMetrics.recordOutboxStage(
                "insert_on_conflict",
                () -> outboxCommandRepository.insertPending(topic, eventKey, serializedPayload)
        );
        if (outboxId == null) {
            log.debug("event=DELIVERY_OUTBOX_ALREADY_EXISTS topic={} eventKey={}", topic, eventKey);
            return;
        }

        log.info("event=DELIVERY_OUTBOX_ENQUEUED outboxId={} topic={} eventKey={}",
                outboxId,
                topic,
                eventKey
        );
    }

    @Scheduled(fixedDelayString = "${delivery.kafka.outbox.fixed-delay-ms:100}")
    public void publishPending() {
        List<DeliveryOutbox> outboxes = findPublishTargets();

        // Kafka 발행 선제 제출
        List<PendingPublish> pendingPublishes = outboxes.stream()
                .map(this::send)
                .toList();

        List<DeliveryOutbox> publishedOutboxes = new ArrayList<>(pendingPublishes.size());
        for (PendingPublish pendingPublish : pendingPublishes) {
            if (awaitPublish(pendingPublish)) {
                publishedOutboxes.add(pendingPublish.outbox());
            }
        }

        // ACK 성공 상태 일괄 반영
        markPublished(publishedOutboxes);
    }

    // 발행 대상 조회 트랜잭션 분리
    private List<DeliveryOutbox> findPublishTargets() {
        // 발행 가능 Outbox 최대 100건 조회
        return transactionTemplate.execute(status ->
                outboxRepository.findTop100ByStatusInAndRetryCountLessThanOrderByCreatedAtAsc(
                        List.of(DeliveryOutbox.Status.PENDING, DeliveryOutbox.Status.FAILED),
                        MAX_RETRY_COUNT
                )
        );
    }

    private PendingPublish send(DeliveryOutbox outbox) {
        try {
            return new PendingPublish(
                    outbox,
                    kafkaTemplate.send(outbox.getTopic(), outbox.getEventKey(), outbox.getPayload())
            );
        } catch (Exception e) {
            return new PendingPublish(outbox, CompletableFuture.failedFuture(e));
        }
    }

    private boolean awaitPublish(PendingPublish pendingPublish) {
        DeliveryOutbox outbox = pendingPublish.outbox();
        try {
            pendingPublish.sendFuture()
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            // 실패 상태 반영
            markFailed(outbox.getOutboxId(), e.getMessage());
            log.error("event=DELIVERY_OUTBOX_PUBLISH_FAILED outboxId={} topic={} eventKey={} retryCount={}",
                    outbox.getOutboxId(),
                    outbox.getTopic(),
                    outbox.getEventKey(),
                    outbox.getRetryCount(),
                    e
            );
            return false;
        }
    }

    private void markPublished(List<DeliveryOutbox> outboxes) {
        if (outboxes.isEmpty()) {
            return;
        }

        outboxCommandRepository.markPublished(
                outboxes.stream()
                        .map(DeliveryOutbox::getOutboxId)
                        .toList()
        );
        outboxes.forEach(outbox ->
                log.info("event=DELIVERY_OUTBOX_PUBLISHED outboxId={} topic={} eventKey={}",
                        outbox.getOutboxId(),
                        outbox.getTopic(),
                        outbox.getEventKey()
                )
        );
    }

    private void markFailed(UUID outboxId, String errorMessage) {
        outboxCommandRepository.markFailed(outboxId, errorMessage);
    }

    private record PendingPublish(
            DeliveryOutbox outbox,
            CompletableFuture<SendResult<String, String>> sendFuture
    ) {
    }
}
