package com.msa.ai_service.entity;

import com.msa.core_common.JpaAuditing.baseEntity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "p_ai_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class AiMessage extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "ai_message_id", nullable = false, updatable = false)
    private UUID aiMessageId;

    @Column(name = "delivery_id", nullable = false)
    private UUID deliveryId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 50)
    private AiRequestType requestType = AiRequestType.DELIVERY_DEADLINE;

    @Column(name = "prompt", nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "request_payload", nullable = false, columnDefinition = "TEXT")
    private String requestPayload;

    @Column(name = "final_departure_deadline")
    private LocalDateTime finalDepartureDeadline;

    @Column(name = "response_content", columnDefinition = "TEXT")
    private String responseContent;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private AiMessageStatus status = AiMessageStatus.PENDING;

    @Builder.Default
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage = "";

    public void markCompleted(
            LocalDateTime finalDepartureDeadline,
            String responseContent
    ) {
        this.status = AiMessageStatus.SUCCESS;
        this.finalDepartureDeadline = finalDepartureDeadline;
        this.responseContent = responseContent;
        this.errorMessage = null;
    }

    public void markFailed(String errorMessage) {
        this.status = AiMessageStatus.FAILED;
        this.errorMessage = errorMessage;
    }

    public void markSkipped(
            LocalDateTime finalDepartureDeadline,
            String responseContent
    ) {
        this.status = AiMessageStatus.SKIPPED;
        this.finalDepartureDeadline = finalDepartureDeadline;
        this.responseContent = responseContent;
        this.errorMessage = null;
    }
}
