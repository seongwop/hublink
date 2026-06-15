package com.msa.delivery_service.service;

import com.msa.core_common.error.exception.CustomException;
import com.msa.delivery_service.enums.DeliveryErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryAssignmentLockService {

    private static final long WAIT_SECOND = 3L;

    private final RedissonClient redissonClient;

    /*
        배송기사 배정을 위한 Lock 설정
        배송 1건에 각 배송 경로들의 배송 기사 N명을 배정하기 때문에 hub_id를 기준으로 Lock을 획득하고
        모든 Lock을 획득한 경우에 function을 호출해서 처리
    */
    public <T> T executeWithLocks(List<String> lockKeys, Supplier<T> function) {
        // 획득한 Lock을 모아두기 위한 List
        List<RLock> acquiredLocks = new ArrayList<>();

        try {
            // 중복 키 제거
            // 키 정렬을 통해 락 획득 순서 통일 -> 데드락 방지
            List<String> sortedKeys = lockKeys.stream()
                    .distinct()
                    .sorted()
                    .toList();
            for (String key : sortedKeys) {
                RLock lock = redissonClient.getLock(key);

                // leaseTime을 주지않고 watchdog 활성화
                boolean locked = lock.tryLock(WAIT_SECOND, TimeUnit.SECONDS);

                // Lock을 획득하지 못할 경우 작업 실패
                // finally에서 Lock 전부 해제
                if (!locked) {
                    log.warn("event=DELIVERY_ASSIGNMENT_LOCK_TIMEOUT keys={} failedKey={} waitSeconds={}",
                            sortedKeys,
                            key,
                            WAIT_SECOND
                    );
                    throw new CustomException(DeliveryErrorCode.DELIVERY_ASSIGNMENT_LOCK_TIMEOUT);
                }

                acquiredLocks.add(lock);
            }
            return function.get();
        } catch (InterruptedException e) {
            // interrupt 상태 복구
            Thread.currentThread().interrupt();
            log.warn("event=DELIVERY_ASSIGNMENT_LOCK_INTERRUPTED keys={} waitSeconds={}",
                    lockKeys,
                    WAIT_SECOND,
                    e
            );
            throw new CustomException(DeliveryErrorCode.DELIVERY_ASSIGNMENT_LOCK_TIMEOUT);
        } finally {
            // 역순으로 Lock 해제
            for (int i = acquiredLocks.size() - 1; i >= 0; i--) {
                RLock lock = acquiredLocks.get(i);
                if (lock.isHeldByCurrentThread()) lock.unlock();
            }
        }
    }
}
