package com.msa.ai_service.stream.metrics;

import com.msa.core_common.stream.DeadlineStreamConstants;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisStreamMetricsCollector {
    private final StringRedisTemplate stringRedisTemplate;
    private final MeterRegistry meterRegistry;

    private final AtomicLong requestedStreamLength = new AtomicLong();
    private final AtomicLong generatedStreamLength = new AtomicLong();
    private final AtomicLong pendingMessages = new AtomicLong();
    private final AtomicLong groupLag = new AtomicLong(-1);
    private final AtomicLong consumerCount = new AtomicLong();
    private final AtomicLong refreshSuccess = new AtomicLong();
    private final AtomicLong lastRefreshEpochSeconds = new AtomicLong();
    private final Map<String, AtomicLong> pendingMessagesByConsumer = new LinkedHashMap<>();

    @Value("${ai.stream.consumer.deadline-requested.concurrency:1}")
    private int concurrency;

    @PostConstruct
    public void registerMetrics() {
        for (int i = 1; i <= concurrency; i++) {
            String consumerName = DeadlineStreamConstants.aiServiceConsumerName(i);
            pendingMessagesByConsumer.put(consumerName, new AtomicLong());
        }

        Gauge.builder("redis.stream.length", requestedStreamLength, AtomicLong::get)
                .description("Redis Stream entry count")
                .tag("stream", DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM)
                .register(meterRegistry);

        Gauge.builder("redis.stream.length", generatedStreamLength, AtomicLong::get)
                .description("Redis Stream entry count")
                .tag("stream", DeadlineStreamConstants.DEADLINE_GENERATED_STREAM)
                .register(meterRegistry);

        Gauge.builder("redis.stream.pending.messages", pendingMessages, AtomicLong::get)
                .description("Redis Stream pending messages in a consumer group")
                .tag("stream", DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM)
                .tag("group", DeadlineStreamConstants.AI_SERVICE_GROUP)
                .register(meterRegistry);

        Gauge.builder("redis.stream.group.lag", groupLag, AtomicLong::get)
                .description("Redis Stream consumer group lag. -1 means Redis did not return lag")
                .tag("stream", DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM)
                .tag("group", DeadlineStreamConstants.AI_SERVICE_GROUP)
                .register(meterRegistry);

        Gauge.builder("redis.stream.consumer.count", consumerCount, AtomicLong::get)
                .description("Redis Stream consumer count in a consumer group")
                .tag("stream", DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM)
                .tag("group", DeadlineStreamConstants.AI_SERVICE_GROUP)
                .register(meterRegistry);

        Gauge.builder("redis.stream.metrics.refresh.success", refreshSuccess, AtomicLong::get)
                .description("Redis Stream metrics refresh success. 1 means success, 0 means failure")
                .register(meterRegistry);

        Gauge.builder("redis.stream.metrics.last.refresh.epoch.seconds", lastRefreshEpochSeconds, AtomicLong::get)
                .description("Last Redis Stream metrics refresh epoch seconds")
                .register(meterRegistry);

        pendingMessagesByConsumer.forEach((consumerName, value) ->
                Gauge.builder("redis.stream.consumer.pending.messages", value, AtomicLong::get)
                        .description("Redis Stream pending messages assigned to a consumer")
                        .tag("stream", DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM)
                        .tag("group", DeadlineStreamConstants.AI_SERVICE_GROUP)
                        .tag("consumer", consumerName)
                        .register(meterRegistry)
        );

        refreshMetrics();
    }

    @Scheduled(fixedDelayString = "${ai.stream.metrics.refresh-fixed-delay-ms:10000}")
    public void refreshMetrics() {
        try {
            requestedStreamLength.set(readStreamLength(DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM));
            generatedStreamLength.set(readStreamLength(DeadlineStreamConstants.DEADLINE_GENERATED_STREAM));
            refreshConsumerGroupMetrics();
            refreshSuccess.set(1);
            lastRefreshEpochSeconds.set(System.currentTimeMillis() / 1000);
        } catch (Exception e) {
            refreshSuccess.set(0);
            log.warn("event=REDIS_STREAM_METRICS_REFRESH_FAILED stream={} group={}",
                    DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM,
                    DeadlineStreamConstants.AI_SERVICE_GROUP,
                    e
            );
        }
    }

    private long readStreamLength(String stream) {
        Long size = stringRedisTemplate.opsForStream().size(stream);
        return size == null ? 0 : size;
    }

    private void refreshConsumerGroupMetrics() {
        PendingMessagesSummary pendingSummary = stringRedisTemplate.opsForStream().pending(
                DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM,
                DeadlineStreamConstants.AI_SERVICE_GROUP
        );

        pendingMessagesByConsumer.values().forEach(value -> value.set(0));

        long totalPending = pendingSummary.getPendingMessagesPerConsumer()
                .values()
                .stream()
                .mapToLong(count -> count == null ? 0L : count.longValue())
                .sum();

        pendingMessages.set(totalPending);

        pendingSummary.getPendingMessagesPerConsumer()
                .forEach((consumerName, count) -> pendingMessagesByConsumer
                        .computeIfAbsent(String.valueOf(consumerName), this::registerConsumerPendingGauge)
                        .set(count == null ? 0 : count.longValue()));

        StreamInfo.XInfoGroups groups = stringRedisTemplate.opsForStream().groups(
                DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM
        );

        StreamInfo.XInfoGroup aiServiceGroup = groups.stream()
                .filter(group -> DeadlineStreamConstants.AI_SERVICE_GROUP.equals(group.groupName()))
                .findFirst()
                .orElse(null);

        if (aiServiceGroup == null) {
            groupLag.set(-1);
            consumerCount.set(0);
            return;
        }

        groupLag.set(readLong(aiServiceGroup.getRaw().get("lag"), -1));
        consumerCount.set(readLong(aiServiceGroup.consumerCount(), 0));
    }

    private long readLong(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Long.parseLong(stringValue);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private AtomicLong registerConsumerPendingGauge(String consumerName) {
        AtomicLong value = new AtomicLong();
        Gauge.builder("redis.stream.consumer.pending.messages", value, AtomicLong::get)
                .description("Redis Stream pending messages assigned to a consumer")
                .tag("stream", DeadlineStreamConstants.DEADLINE_REQUESTED_STREAM)
                .tag("group", DeadlineStreamConstants.AI_SERVICE_GROUP)
                .tag("consumer", consumerName)
                .register(meterRegistry);
        return value;
    }
}
