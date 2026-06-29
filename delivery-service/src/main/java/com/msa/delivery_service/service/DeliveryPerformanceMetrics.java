package com.msa.delivery_service.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class DeliveryPerformanceMetrics {

    private static final String UNKNOWN = "unknown";

    private final MeterRegistry meterRegistry;

    // 계측 시작 시각 반환
    public long start() {
        return System.nanoTime();
    }

    // 배송 생성 단계별 처리 시간 기록
    public void recordCreateStage(String stage, long startNanos) {
        recordTimer("delivery.create.stage", elapsed(startNanos), "stage", stage);
    }

    // 배송 생성 단계별 처리 시간 기록
    public void recordCreateStage(String stage, Runnable runnable) {
        long startNanos = start();
        try {
            runnable.run();
        } finally {
            recordCreateStage(stage, startNanos);
        }
    }

    // 배송 생성 단계별 처리 시간 기록
    public <T> T recordCreateStage(String stage, Supplier<T> supplier) {
        long startNanos = start();
        try {
            return supplier.get();
        } finally {
            recordCreateStage(stage, startNanos);
        }
    }

    // Outbox 단계별 처리 시간 기록
    public void recordOutboxStage(String stage, long startNanos) {
        recordTimer("delivery.outbox.stage", elapsed(startNanos), "stage", stage);
    }

    // Outbox 단계별 처리 시간 기록
    public <T> T recordOutboxStage(String stage, Supplier<T> supplier) {
        long startNanos = start();
        try {
            return supplier.get();
        } finally {
            recordOutboxStage(stage, startNanos);
        }
    }

    // 배송 담당자 배정 집계 처리 시간 기록
    public void recordAssignmentCountOperation(String assignmentType, String operation, long startNanos) {
        recordTimer(
                "delivery.assignment.count.operation",
                elapsed(startNanos),
                "assignment_type", normalize(assignmentType),
                "operation", operation
        );
    }

    // 배송 담당자 배정 집계 처리 시간 기록
    public void recordAssignmentCountOperation(String assignmentType, String operation, Runnable runnable) {
        long startNanos = start();
        try {
            runnable.run();
        } finally {
            recordAssignmentCountOperation(assignmentType, operation, startNanos);
        }
    }

    // 배송 담당자 배정 집계 처리 시간 기록
    public <T> T recordAssignmentCountOperation(String assignmentType, String operation, Supplier<T> supplier) {
        long startNanos = start();
        try {
            return supplier.get();
        } finally {
            recordAssignmentCountOperation(assignmentType, operation, startNanos);
        }
    }

    // 배송 담당자 배정 Lock 획득 대기 시간 기록
    public void recordLockWait(String lockType, String result, long startNanos) {
        recordTimer(
                "delivery.assignment.lock.wait",
                elapsed(startNanos),
                "lock_type", normalize(lockType),
                "result", result
        );
    }

    // 배송 담당자 배정 Lock 점유 시간 기록
    public void recordLockHold(String lockScope, String result, long startNanos) {
        recordTimer(
                "delivery.assignment.lock.hold",
                elapsed(startNanos),
                "lock_scope", normalize(lockScope),
                "result", result
        );
    }

    // 배송 담당자 배정 Lock 타임아웃 횟수 기록
    public void incrementLockTimeout(String lockType) {
        meterRegistry.counter(
                "delivery.assignment.lock.timeout",
                "lock_type", normalize(lockType)
        ).increment();
    }

    // Timer 메트릭 기록
    private void recordTimer(String name, Duration duration, String... tags) {
        meterRegistry.timer(name, tags).record(duration);
    }

    // 계측 경과 시간 계산
    private Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    // 계측 태그 기본값 처리
    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        return value.toLowerCase();
    }
}
