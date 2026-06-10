package com.msa.delivery_service.message;

import com.msa.core_common.stream.DeadlineStreamConstants;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@DependsOn("deadlineGeneratedStreamGroupInitializer")
public class DeadlineGeneratedPendingRetryConsumer {
    private static final long MAX_DELIVERY_COUNT = 5L;
    private static final long PENDING_SCAN_COUNT = 100L;
    private static final Duration PENDING_MIN_IDLE_TIME = Duration.ofMinutes(1);

    private final StringRedisTemplate stringRedisTemplate;
    private final DeadlineGeneratedStreamConsumer streamConsumer;
    private final DeadlineGeneratedStreamGroupInitializer streamGroupInitializer;

    @Scheduled(fixedDelay = 300_000)
    public void retryPendingMessages() {
        StreamOperations<String, Object, Object> streamOps = stringRedisTemplate.opsForStream();

        // 현재 consumer의 PEL 조회
        PendingMessages pendingMessages;
        try {
            pendingMessages = streamOps.pending(
                    DeadlineStreamConstants.DEADLINE_GENERATED_STREAM,
                    Consumer.from(
                            DeadlineStreamConstants.DELIVERY_SERVICE_GROUP,
                            DeadlineStreamConstants.DELIVERY_SERVICE_CONSUMER
                    ),
                    Range.unbounded(),
                    PENDING_SCAN_COUNT
            );
        } catch (RedisSystemException e) {
            if (isNoGroup(e)) {
                log.warn("event=DELIVERY_PENDING_RETRY_GROUP_MISSING stream={} group={}",
                        DeadlineStreamConstants.DEADLINE_GENERATED_STREAM,
                        DeadlineStreamConstants.DELIVERY_SERVICE_GROUP
                );
                streamGroupInitializer.createConsumerGroup();
                return;
            }
            throw e;
        }

        if (pendingMessages.isEmpty()) {
            return;
        }

        for (PendingMessage pendingMessage : pendingMessages) {
            // MIN_IDLE_TIME을 설정하여 메인 스트림이 처리 중인 메세지는 스킵
            if (pendingMessage.getElapsedTimeSinceLastDelivery().compareTo(PENDING_MIN_IDLE_TIME) < 0) {
                continue;
            }
            retryPendingMessage(streamOps, pendingMessage);
        }
    }

    private void retryPendingMessage(
            StreamOperations<String, Object, Object> streamOps,
            PendingMessage pendingMessage
    ) {
        MapRecord<String, Object, Object> targetRecord = claimRecord(streamOps, pendingMessage.getId());
        // PEL에는 남아있으나 메인 스트림에 없는 경우
        if (targetRecord == null) {
            acknowledge(streamOps, pendingMessage.getId());
            return;
        }

        try {
            streamConsumer.process(targetRecord);
            acknowledge(streamOps, targetRecord.getId());
            log.info("event=DELIVERY_PENDING_RETRY_COMPLETED recordId={}", targetRecord.getId());
        } catch (Exception e) {
            long nextDeliveryCount = pendingMessage.getTotalDeliveryCount() + 1;
            if (nextDeliveryCount >= MAX_DELIVERY_COUNT) {
                moveToDlqAndAcknowledge(streamOps, targetRecord, nextDeliveryCount);
                return;
            }
            log.error("event=DELIVERY_PENDING_RETRY_FAILED recordId={} deliveryCount={}",
                    pendingMessage.getId(),
                    nextDeliveryCount,
                    e
            );
        }
    }

    private void moveToDlqAndAcknowledge(
            StreamOperations<String, Object, Object> streamOps,
            MapRecord<String, Object, Object> targetRecord,
            long deliveryCount
    ) {
        streamOps.add(
                DeadlineStreamConstants.DEADLINE_GENERATED_DELIVERY_DLQ_STREAM,
                Map.of("payload", String.valueOf(targetRecord.getValue().get("payload")))
        );
        acknowledge(streamOps, targetRecord.getId());
        log.warn("event=DELIVERY_PENDING_DLQ_MOVED recordId={} deliveryCount={}",
                targetRecord.getId(),
                deliveryCount
        );
    }

    private MapRecord<String, Object, Object> claimRecord(
            StreamOperations<String, Object, Object> streamOps,
            RecordId recordId
    ) {
        List<MapRecord<String, Object, Object>> claimedRecords = streamOps.claim(
                DeadlineStreamConstants.DEADLINE_GENERATED_STREAM,
                DeadlineStreamConstants.DELIVERY_SERVICE_GROUP,
                DeadlineStreamConstants.DELIVERY_SERVICE_CONSUMER,
                PENDING_MIN_IDLE_TIME,
                recordId
        );

        if (claimedRecords == null || claimedRecords.isEmpty()) {
            log.warn("event=DELIVERY_PENDING_CLAIM_FAILED recordId={}", recordId);
            return null;
        }

        return claimedRecords.get(0);
    }

    private void acknowledge(StreamOperations<String, Object, Object> streamOps, RecordId recordId) {
        streamOps.acknowledge(
                DeadlineStreamConstants.DEADLINE_GENERATED_STREAM,
                DeadlineStreamConstants.DELIVERY_SERVICE_GROUP,
                recordId
        );
    }

    private boolean isNoGroup(RedisSystemException e) {
        Throwable cause = e.getCause();
        return (e.getMessage() != null && e.getMessage().contains("NOGROUP"))
                || (cause != null && cause.getMessage() != null && cause.getMessage().contains("NOGROUP"));
    }
}
