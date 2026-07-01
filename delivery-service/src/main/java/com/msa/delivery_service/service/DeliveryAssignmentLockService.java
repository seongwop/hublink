package com.msa.delivery_service.service;

import com.msa.core_common.error.exception.CustomException;
import com.msa.delivery_service.enums.DeliveryErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryAssignmentLockService {

    private final RedissonClient redissonClient;
    private final DeliveryPerformanceMetrics performanceMetrics;

    @Value("${delivery.assignment.lock-wait:2s}")
    private Duration lockWait;

    /*
        배송기사 배정을 위한 Lock 설정
        배송 1건에 각 배송 경로들의 배송 기사 N명을 배정하기 때문에 hub_id를 기준으로 Lock을 획득하고
        모든 Lock을 획득한 경우에 function을 호출해서 처리
    */
    public <T> T executeWithLocks(List<String> lockKeys, Supplier<T> function) {
        // 획득한 Lock을 모아두기 위한 List
        List<RLock> acquiredLocks = new ArrayList<>();
        // Lock 획득 대기 시간 계측을 위한 상태값
        List<String> sortedKeys = List.of();
        String currentKey = null;
        long waitStartNanos = 0L;

        try {
            // 중복 키 제거
            // Lock 유형 우선순위와 키 정렬을 통해 락 획득 순서 통일 -> 데드락 방지
            sortedKeys = lockKeys.stream()
                    .distinct()
                    .sorted(Comparator.<String>comparingInt(this::lockOrder).thenComparing(Comparator.naturalOrder()))
                    .toList();

            for (String key : sortedKeys) {
                currentKey = key;
                RLock lock = redissonClient.getLock(key);

                // leaseTime을 주지않고 watchdog 활성화
                // Lock 획득 대기 시간 계측
                waitStartNanos = performanceMetrics.start();
                boolean locked = lock.tryLock(lockWait.toMillis(), TimeUnit.MILLISECONDS);
                performanceMetrics.recordLockWait(lockType(key), locked ? "acquired" : "timeout", waitStartNanos);

                // Lock을 획득하지 못할 경우 작업 실패
                // finally에서 Lock 전부 해제
                if (!locked) {
                    performanceMetrics.incrementLockTimeout(lockType(key));
                    log.warn("event=DELIVERY_ASSIGNMENT_LOCK_TIMEOUT keys={} failedKey={} waitMillis={}",
                            sortedKeys,
                            key,
                            lockWait.toMillis()
                    );
                    throw new CustomException(DeliveryErrorCode.DELIVERY_ASSIGNMENT_LOCK_TIMEOUT);
                }

                acquiredLocks.add(lock);
            }

            // 모든 Lock을 획득한 뒤 실제 작업 점유 시간 계측
            long holdStartNanos = performanceMetrics.start();
            try {
                T result = function.get();
                performanceMetrics.recordLockHold(lockScope(sortedKeys), "success", holdStartNanos);
                return result;
            } catch (RuntimeException | Error e) {
                performanceMetrics.recordLockHold(lockScope(sortedKeys), "error", holdStartNanos);
                throw e;
            }
        } catch (InterruptedException e) {
            // interrupt 상태 복구
            if (currentKey != null && waitStartNanos > 0) {
                performanceMetrics.recordLockWait(lockType(currentKey), "interrupted", waitStartNanos);
                performanceMetrics.incrementLockTimeout(lockType(currentKey));
            }
            Thread.currentThread().interrupt();
            log.warn("event=DELIVERY_ASSIGNMENT_LOCK_INTERRUPTED keys={} waitMillis={}",
                    lockKeys,
                    lockWait.toMillis(),
                    e
            );
            throw new CustomException(DeliveryErrorCode.DELIVERY_ASSIGNMENT_LOCK_TIMEOUT);
        } finally {
            // 역순으로 Lock 해제
            for (int i = acquiredLocks.size() - 1; i >= 0; i--) {
                RLock lock = acquiredLocks.get(i);
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }

    // 계측 태그에 사용할 Lock 유형 구분
    private String lockType(String lockKey) {
        if (lockKey == null) {
            return "unknown";
        }
        if (lockKey.contains(":company:")) {
            return "company";
        }
        if (lockKey.contains(":hub:")) {
            return "hub";
        }
        return "unknown";
    }

    // Lock 획득 순서 우선순위
    private int lockOrder(String lockKey) {
        return switch (lockType(lockKey)) {
            case "hub" -> 0;
            case "company" -> 1;
            default -> 2;
        };
    }

    // 계측 태그에 사용할 Lock 범위 구분
    private String lockScope(List<String> lockKeys) {
        boolean hasCompany = false;
        boolean hasHub = false;

        for (String lockKey : lockKeys) {
            if ("company".equals(lockType(lockKey))) {
                hasCompany = true;
            }
            if ("hub".equals(lockType(lockKey))) {
                hasHub = true;
            }
        }

        if (hasCompany && hasHub) {
            return "mixed";
        }
        if (hasCompany) {
            return "company";
        }
        if (hasHub) {
            return "hub";
        }
        return "unknown";
    }
}
