package com.msa.ai_service.service;

import com.msa.ai_service.dto.AiTestStreamPublishResponse;
import com.msa.ai_service.stream.event.DeadlineGeneratedEvent;
import com.msa.ai_service.stream.publisher.DeadlineGeneratedEventPublisher;
import com.msa.core_common.stream.DeadlineStreamConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiTestService {
    private final DeadlineGeneratedEventPublisher deadlineGeneratedEventPublisher;

    public AiTestStreamPublishResponse publishDeadlineGeneratedEvent(
            DeadlineGeneratedEvent event
    ) {
        RecordId recordId = deadlineGeneratedEventPublisher.publish(event);

        log.info("event=AI_TEST_DEADLINE_GENERATED_EVENT_PUBLISHED stream={} recordId={} eventId={} deliveryId={} aiMessageId={}",
                DeadlineStreamConstants.DEADLINE_GENERATED_STREAM,
                recordId,
                event.getEventId(),
                event.getDeliveryId(),
                event.getAiMessageId()
        );

        return AiTestStreamPublishResponse.builder()
                .streamKey(DeadlineStreamConstants.DEADLINE_GENERATED_STREAM)
                .recordId(recordId == null ? null : recordId.getValue())
                .eventId(event.getEventId())
                .deliveryId(event.getDeliveryId())
                .aiMessageId(event.getAiMessageId())
                .message("DeadlineGeneratedEvent published")
                .build();
    }
}
