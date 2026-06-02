package com.msa.slack_service.entity;

import com.msa.core_common.JpaAuditing.baseEntity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Entity
@Table(name = "p_slack_messages")
public class SlackMessage extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "slack_message_id")
    private UUID slackMessageId;

    @Column(name = "receiver_user_id", nullable = false)
    private UUID receiverUserId;

    @Column(name = "ai_message_id")
    private UUID aiMessageId;

    @Column(name = "receiver_slack_id", nullable = false, length = 100)
    private String receiverSlackId;

    @Column(name = "idempotency_key", nullable = false, length = 255, unique = true)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 50)
    private MessageType messageType;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private SlackMessageStatus status = SlackMessageStatus.PENDING;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;


    public void markSent() {
        this.status = SlackMessageStatus.SENT;
        this.sentAt = LocalDateTime.now();
        this.errorMessage = null;
    }

    public void markFailed(String errorMessage) {
        this.status = SlackMessageStatus.FAILED;
        this.errorMessage = errorMessage;
    }

    public void markSkipped(String reason) {
        this.status = SlackMessageStatus.SKIPPED;
        this.errorMessage = reason;
    }
}
