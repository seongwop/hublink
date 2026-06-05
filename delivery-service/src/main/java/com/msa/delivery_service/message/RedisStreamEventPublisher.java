package com.msa.delivery_service.message;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.core_common.error.exception.CustomException;
import com.msa.delivery_service.enums.DeliveryErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class RedisStreamEventPublisher {

    public static final String DEADLINE_REQUESTED_STREAM = "deadline:requested:stream";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public void publishAfterCommit(String streamKey, Object event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.info("event=REDIS_STREAM_PUBLISH_IMMEDIATE stream={} eventType={}",
                    streamKey,
                    event.getClass().getSimpleName()
            );
            publish(streamKey, event);
            return;
        }
        // 콜백 함수 등록
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.info("event=REDIS_STREAM_AFTER_COMMIT stream={} eventType={}",
                        streamKey,
                        event.getClass().getSimpleName()
                );
                publish(streamKey, event);
            }
        });
    }

    private void publish(String streamKey, Object event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            RecordId recordId = stringRedisTemplate.opsForStream().add(streamKey, Map.of("payload", payload));
            log.info("event=REDIS_STREAM_PUBLISHED stream={} recordId={} eventType={}",
                    streamKey,
                    recordId,
                    event.getClass().getSimpleName()
            );
        } catch (JsonProcessingException e) {
            log.error("event=REDIS_STREAM_PUBLISH_FAILED stream={} eventType={}",
                    streamKey,
                    event.getClass().getSimpleName(),
                    e
            );
            throw new CustomException(DeliveryErrorCode.AI_SCHEDULE_EVENT_PUBLISH_FAILED);
        }
    }
}
