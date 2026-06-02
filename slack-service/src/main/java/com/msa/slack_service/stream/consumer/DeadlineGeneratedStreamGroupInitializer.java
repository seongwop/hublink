package com.msa.slack_service.stream.consumer;

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
public class DeadlineGeneratedStreamGroupInitializer {
    private final StringRedisTemplate stringRedisTemplate;

    @PostConstruct
    public void createConsumerGroup() {
        try {
            // Slack 발송 스트림 그룹 생성
            stringRedisTemplate.opsForStream().createGroup(
                    DeadlineStreamConstants.DEADLINE_GENERATED_STREAM,
                    ReadOffset.from("0"),
                    DeadlineStreamConstants.SLACK_SERVICE_GROUP
            );
            log.info("Redis Stream consumer group 생성 완료. stream={}, group={}",
                    DeadlineStreamConstants.DEADLINE_GENERATED_STREAM,
                    DeadlineStreamConstants.SLACK_SERVICE_GROUP
            );
        } catch (RedisSystemException e) {
            if (isBusyGroup(e)) {
                log.info("Redis Stream consumer group 기존 존재. stream={}, group={}",
                        DeadlineStreamConstants.DEADLINE_GENERATED_STREAM,
                        DeadlineStreamConstants.SLACK_SERVICE_GROUP
                );
                return;
            }
            log.error("Redis Stream consumer group 생성 실패. stream={}, group={}",
                    DeadlineStreamConstants.DEADLINE_GENERATED_STREAM,
                    DeadlineStreamConstants.SLACK_SERVICE_GROUP,
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
