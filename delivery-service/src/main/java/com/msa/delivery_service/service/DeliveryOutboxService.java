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
    // Outbox 단계별 처리 시간 계측
    private final DeliveryPerformanceMetrics performanceMetrics;

    @Transactional
    public void enqueue(String topic, String eventKey, Object payload) {
        /*
            객체 payload -> JSON 문자열로 변환
        */
        String serializedPayload;
        // payload 직렬화 처리 시간 계측
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

    @Scheduled(fixedDelayString = "${delivery.kafka.outbox.fixed-delay-ms:1000}")
    public void publishPending() {
        /*
            outbox worker
            서비스 내부 스케줄러가 주기적으로 미발행 row 조회
        */
        List<DeliveryOutbox> outboxes = findPublishTargets();

        // Kafka 발행 요청 선제 제출
        List<PendingPublish> pendingPublishes = outboxes.stream()
                .map(this::send)
                .toList();

        // ACK 결과 확인 후 상태 순차 반영
        pendingPublishes.forEach(this::completePublish);
    }

    // self-invocation 문제를 피하기 위해 별도 트랜잭션 생성
    private List<DeliveryOutbox> findPublishTargets() {
        return transactionTemplate.execute(status ->
                // PENDING, FAILED 상태 중 재시도 횟수 제한 미만인 것만 오래된 것부터 100개씩 처리
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

    private void completePublish(PendingPublish pendingPublish) {
        DeliveryOutbox outbox = pendingPublish.outbox();
        try {
            pendingPublish.sendFuture()
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS); // 브로커로부터 ack 수신 대기
            // 전송 성공을 확인한 경우만 상태 변경
            markPublished(outbox.getOutboxId());
            log.info("event=DELIVERY_OUTBOX_PUBLISHED outboxId={} topic={} eventKey={}",
                    outbox.getOutboxId(),
                    outbox.getTopic(),
                    outbox.getEventKey()
            );
        } catch (Exception e) {
            // 상태 변경 및 추후 재처리
            markFailed(outbox.getOutboxId(), e.getMessage());
            log.error("event=DELIVERY_OUTBOX_PUBLISH_FAILED outboxId={} topic={} eventKey={} retryCount={}",
                    outbox.getOutboxId(),
                    outbox.getTopic(),
                    outbox.getEventKey(),
                    outbox.getRetryCount(),
                    e
            );
        }
    }

    // 상태 변경 로직 분리
    private void markPublished(UUID outboxId) {
        outboxCommandRepository.markPublished(outboxId);
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
