package com.msa.order_service.client.circuit;

import com.msa.order_service.client.UserFeignClient;
import com.msa.order_service.dto.res.UsernameResDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserCircuitService {

    private final UserFeignClient userFeignClient;

    @CircuitBreaker(name = "userRead", fallbackMethod = "getUserNamesFallback")
    public List<UsernameResDto> getUserNames(List<UUID> userIds) {
        return userFeignClient.getUserNames(userIds);
    }

    public List<UsernameResDto> getUserNamesFallback(List<UUID> userIds, Throwable t) {
        log.error("event=ORDER_USER_NAMES_FALLBACK reason={}", t.getMessage());

        // 유저 서버가 죽어도 주문 목록은 나오게 "알 수 없는 유저"로 채워서 리턴
        return userIds.stream()
                .map(id -> new UsernameResDto(id, "존재하지 않는 유저(서비스 점검 중)", null, null))
                .toList();
    }
}
