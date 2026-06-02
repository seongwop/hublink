package com.msa.ai_service.service;

import com.msa.ai_service.entity.AiMessage;
import com.msa.ai_service.entity.AiMessageStatus;
import com.msa.ai_service.entity.AiRequestType;
import com.msa.ai_service.exception.AiErrorCode;
import com.msa.ai_service.repository.AiMessageRepository;
import com.msa.ai_service.dto.AiMessageResponse;
import com.msa.core_common.auth.UserRole;
import com.msa.core_common.error.exception.CustomException;
import com.msa.core_common.response.paging.PageRes;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AiMessageService {
    private final AiMessageRepository aiMessageRepository;

    // 요청 이력 저장
    @Transactional
    public AiMessage saveMessage(
            UUID deliveryId,
            AiRequestType requestType,
            String prompt,
            String requestPayload
    ) {
        return aiMessageRepository.save(
                AiMessage.builder()
                        .deliveryId(deliveryId)
                        .requestType(requestType)
                        .prompt(prompt)
                        .requestPayload(requestPayload)
                        .build()
        );
    }

    // 요청 성공
    @Transactional
    public void markCompleted(
            UUID aiMessageId,
            LocalDateTime finalDepartureDeadline,
            String responseContent
    ) {
        AiMessage aiMessage = getAiMessageEntity(aiMessageId);
        aiMessage.markCompleted(finalDepartureDeadline, responseContent);
    }

    // 요청 실패
    @Transactional
    public void markFailed(UUID aiMessageId, String reason) {
        AiMessage aiMessage = getAiMessageEntity(aiMessageId);
        aiMessage.markFailed(reason);
    }

    @Transactional
    public void markSkipped(
            UUID aiMessageId,
            LocalDateTime finalDepartureDeadline,
            String responseContent
    ) {
        AiMessage aiMessage = getAiMessageEntity(aiMessageId);
        aiMessage.markSkipped(finalDepartureDeadline, responseContent);
    }

    // 요청 목록 조회
    public PageRes<AiMessageResponse> getAiMessages(
            String role,
            AiRequestType requestType,
            AiMessageStatus status,
            Pageable pageable
    ) {
        validateMaster(role);

        Page<AiMessageResponse> page = aiMessageRepository
                .findAllByCondition(requestType, status, pageable)
                .map(AiMessageResponse::from);

        return new PageRes<>(page);
    }

    // 요청 상세 조회
    public AiMessageResponse getAiMessage(String role, UUID aiMessageId) {
        validateMaster(role);

        AiMessage aiMessage = getAiMessageEntity(aiMessageId);
        return AiMessageResponse.from(aiMessage);
    }

    public AiMessage getAiMessageEntity(UUID aiMessageId) {
        return aiMessageRepository.findByAiMessageIdAndDeletedAtIsNull(aiMessageId)
                .orElseThrow(() -> new CustomException(AiErrorCode.AI_MESSAGE_NOT_FOUND));
    }

    // 권한 검증
    private void validateMaster(String role) {
        if (!UserRole.MASTER.name().equals(role)) {
            throw new CustomException(AiErrorCode.AI_MESSAGE_ACCESS_DENIED);
        }
    }
}
