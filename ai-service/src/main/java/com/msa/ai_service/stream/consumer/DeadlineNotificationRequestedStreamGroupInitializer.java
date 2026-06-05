package com.msa.ai_service.stream.consumer;

import com.msa.core_common.stream.DeadlineStreamConstants;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class DeadlineNotificationRequestedStreamGroupInitializer {
    private final StringRedisTemplate stringRedisTemplate;

    @PostConstruct
    public void createConsumerGroup() {
        try {
            // AI 요청 스트림 그룹 생성
            stringRedisTemplate.opsForStream().createGroup(
                    DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM,
                    ReadOffset.from("0"),
                    DeadlineStreamConstants.AI_SERVICE_GROUP
            );
            log.info("event=AI_STREAM_GROUP_CREATED stream={} group={}",
                    DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM,
                    DeadlineStreamConstants.AI_SERVICE_GROUP
            );
        } catch (RedisSystemException e) {
            if (isBusyGroup(e)) {
                log.info("event=AI_STREAM_GROUP_EXISTS stream={} group={}",
                        DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM,
                        DeadlineStreamConstants.AI_SERVICE_GROUP
                );
                return;
            }
            log.error("event=AI_STREAM_GROUP_CREATE_FAILED stream={} group={}",
                    DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM,
                    DeadlineStreamConstants.AI_SERVICE_GROUP,
                    e
            );
            throw e;
        }
    }

    private boolean isBusyGroup(RedisSystemException e) {
        Throwable cause = e.getCause();
        return (e.getMessage() != null && e.getMessage().contains("BUSYGROUP"))
                || (cause != null && cause.getMessage() != null && cause.getMessage().contains("BUSYGROUP"));
    }
}
