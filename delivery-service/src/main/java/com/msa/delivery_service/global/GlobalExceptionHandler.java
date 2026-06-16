package com.msa.delivery_service.global;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.msa.core_common.error.ErrorResponse;
import com.msa.core_common.error.exception.CustomException;
import com.msa.core_common.response.GlobalResponse;
import com.msa.delivery_service.enums.DeliveryErrorCode;
import feign.FeignException;
import jakarta.persistence.OptimisticLockException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<GlobalResponse<?>> handleCustomException(CustomException e) {
        // 도메인에서 정의한 ErrorCode를 그대로 HTTP 응답으로 변환
        ErrorResponse errorResponse = ErrorResponse.of(e.getErrorCode().getCode(), e.getMessage());
        GlobalResponse<?> response = GlobalResponse.failure(
                e.getErrorCode().getStatus().value(),
                e.getErrorCode().getCode(),
                errorResponse
        );

        return ResponseEntity.status(e.getErrorCode().getStatus()).body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<GlobalResponse<?>> handleValidationException(MethodArgumentNotValidException e) {
        // @Valid 실패는 필드별 메시지 반환
        Map<String, String> errors = new HashMap<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            errors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }

        ErrorResponse errorResponse = ErrorResponse.of("VALIDATION_ERROR", errors.toString());
        GlobalResponse<?> response = GlobalResponse.failure(
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_ERROR",
                errorResponse
        );

        return ResponseEntity.badRequest().body(response);
    }

    // 요청 본문 파싱 실패 응답
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<GlobalResponse<?>> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.warn("event=DELIVERY_REQUEST_BODY_INVALID message={}", e.getMessage());

        ErrorResponse errorResponse = ErrorResponse.of(
                "INVALID_REQUEST_BODY",
                resolveRequestBodyErrorMessage(e)
        );
        GlobalResponse<?> response = GlobalResponse.failure(
                HttpStatus.BAD_REQUEST.value(),
                "INVALID_REQUEST_BODY",
                errorResponse
        );

        return ResponseEntity.badRequest().body(response);
    }

    // 낙관적 락 예외 처리
    @ExceptionHandler({
            ObjectOptimisticLockingFailureException.class,
            OptimisticLockException.class
    })
    public ResponseEntity<GlobalResponse<?>> handleOptimisticLockException(Exception e) {
        log.warn("event=DELIVERY_CONCURRENCY_CONFLICT message={}", e.getMessage());

        ErrorResponse errorResponse = ErrorResponse.of(
                DeliveryErrorCode.CONCURRENT_DELIVERY_UPDATE.getCode(),
                DeliveryErrorCode.CONCURRENT_DELIVERY_UPDATE.getMessage()
        );
        GlobalResponse<?> response = GlobalResponse.failure(
                DeliveryErrorCode.CONCURRENT_DELIVERY_UPDATE.getStatus().value(),
                DeliveryErrorCode.CONCURRENT_DELIVERY_UPDATE.getCode(),
                errorResponse
        );

        return ResponseEntity.status(DeliveryErrorCode.CONCURRENT_DELIVERY_UPDATE.getStatus()).body(response);
    }

    // 4xx/5xx 응답은 FeignException 발생
    @ExceptionHandler(FeignException.class)
    public ResponseEntity<GlobalResponse<?>> handleFeignException(FeignException e) {
        log.warn("event=DELIVERY_DOWNSTREAM_CALL_FAILED status={} message={}", e.status(), e.getMessage());

        ErrorResponse errorResponse = ErrorResponse.of(
                "DOWNSTREAM_SERVICE_ERROR",
                "하위 서비스와 통신하는 중 오류가 발생했습니다."
        );
        GlobalResponse<?> response = GlobalResponse.failure(
                HttpStatus.BAD_GATEWAY.value(),
                "DOWNSTREAM_SERVICE_ERROR",
                errorResponse
        );

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<GlobalResponse<?>> handleException(Exception e) {
        log.error("event=DELIVERY_UNEXPECTED_EXCEPTION", e);

        ErrorResponse errorResponse = ErrorResponse.of(
                "INTERNAL_SERVER_ERROR",
                "서버 내부 오류가 발생했습니다."
        );
        GlobalResponse<?> response = GlobalResponse.failure(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "INTERNAL_SERVER_ERROR",
                errorResponse
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    // 요청 본문 오류 메시지 정리
    private String resolveRequestBodyErrorMessage(HttpMessageNotReadableException e) {
        Throwable cause = e.getCause();
        if (cause instanceof InvalidFormatException invalidFormatException) {
            String fieldPath = invalidFormatException.getPath().stream()
                    .map(reference -> reference.getFieldName())
                    .filter(fieldName -> fieldName != null && !fieldName.isBlank())
                    .reduce((left, right) -> left + "." + right)
                    .orElse("requestBody");

            if (invalidFormatException.getTargetType() == UUID.class) {
                return String.format("요청 본문 필드 '%s'는 UUID 형식이어야 합니다.", fieldPath);
            }

            return String.format(
                    "요청 본문 필드 '%s'의 값 형식이 올바르지 않습니다. 기대 타입: %s",
                    fieldPath,
                    invalidFormatException.getTargetType().getSimpleName()
            );
        }

        return "요청 본문 형식이 올바르지 않습니다.";
    }
}
