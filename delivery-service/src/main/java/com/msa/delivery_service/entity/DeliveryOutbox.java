package com.msa.delivery_service.entity;

import com.msa.core_common.JpaAuditing.baseEntity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Entity
@Table(
        name = "p_delivery_outboxes",
        schema = "delivery_service",
        uniqueConstraints = {
                // 같은 topic + event_key 중복 저장 방지
                @UniqueConstraint(
                        name = "uk_delivery_outbox_topic_event_key",
                        columnNames = {"topic", "event_key"}
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeliveryOutbox extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "outbox_id", nullable = false)
    private UUID outboxId;

    @Column(name = "topic", nullable = false, length = 100)
    private String topic;

    @Column(name = "event_key", nullable = false, length = 100)
    private String eventKey;

    @Lob
    @Column(name = "payload", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Lob
    @Column(name = "last_error")
    private String lastError;

    private DeliveryOutbox(String topic, String eventKey, String payload) {
        this.topic = topic;
        this.eventKey = eventKey;
        this.payload = payload;
        this.status = Status.PENDING;
        this.retryCount = 0;
    }

    public static DeliveryOutbox create(String topic, String eventKey, String payload) {
        return new DeliveryOutbox(topic, eventKey, payload);
    }

    public void markPublished() {
        this.status = Status.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
        this.lastError = null; // 이전 실패 흔적 lastError 초기화
    }

    public void markFailed(String errorMessage) {
        this.status = Status.FAILED;
        this.retryCount++;
        // 에러 메시지 길이 제한
        this.lastError = (errorMessage == null || errorMessage.length() <= 1000)
                ? errorMessage : errorMessage.substring(0, 1000);
    }

    public enum Status {
        PENDING,
        PUBLISHED,
        FAILED
    }
}
