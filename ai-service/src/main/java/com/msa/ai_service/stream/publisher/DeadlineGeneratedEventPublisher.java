package com.msa.ai_service.stream.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.ai_service.exception.AiErrorCode;
import com.msa.ai_service.stream.event.DeadlineGeneratedEvent;
import com.msa.core_common.error.exception.CustomException;
import com.msa.core_common.stream.DeadlineStreamConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class DeadlineGeneratedEventPublisher {
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public RecordId publish(DeadlineGeneratedEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);

            RecordId recordId = stringRedisTemplate.opsForStream().add(
                    DeadlineStreamConstants.DEADLINE_GENERATED_STREAM,
                    Map.of("payload", payload)
            );
            log.info("event=AI_DEADLINE_GENERATED_PUBLISHED stream={} recordId={} deliveryId={} aiMessageId={}",
                    DeadlineStreamConstants.DEADLINE_GENERATED_STREAM,
                    recordId,
                    event.getDeliveryId(),
                    event.getAiMessageId()
            );
            return recordId;

        } catch (Exception e) {
            log.error("event=AI_DEADLINE_GENERATED_PUBLISH_FAILED stream={} deliveryId={} aiMessageId={}",
                    DeadlineStreamConstants.DEADLINE_GENERATED_STREAM,
                    event.getDeliveryId(),
                    event.getAiMessageId(),
                    e
            );
            throw new CustomException(AiErrorCode.AI_EVENT_PUBLISH_FAILED);
        }
    }
}
