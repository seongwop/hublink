package com.msa.order_service.client.circuit;

import com.msa.order_service.client.CompanyFeignClient;
import com.msa.order_service.dto.res.CompanyAddressResDto;
import com.msa.order_service.dto.res.CompanyNameResDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CompanyCircuitService {

    private final CompanyFeignClient companyFeignClient;

    @CircuitBreaker(name = "companyRead", fallbackMethod = "getCompanyNamesFallback")
    public List<CompanyNameResDto> getCompanyNames(List<UUID> companyIds) {
        return companyFeignClient.getCompanyNames(companyIds);
    }

    public List<CompanyNameResDto> getCompanyNamesFallback(List<UUID> companyIds, Throwable t) {
        log.error("event=ORDER_COMPANY_NAMES_FALLBACK reason={}", t.getMessage());

        // 업체 서버가 죽어도 주문 목록은 나오게 "임시 업체"로 채워서 리턴
        return companyIds.stream()
                .map(id -> new CompanyNameResDto(id, "존재하지 않는 업체(서비스 점검 중)"))
                .toList();
    }

    @CircuitBreaker(name = "companyAddress", fallbackMethod = "companyAddressFallback")
    public CompanyAddressResDto companyAddress(UUID companyId) {
        return companyFeignClient.getCompanyAddress(companyId);
    }

    public CompanyAddressResDto companyAddressFallback(UUID companyId, Throwable t) {
        log.error("event=ORDER_COMPANY_ADDRESS_FALLBACK companyId={} reason={}", companyId, t.getMessage());
        return new CompanyAddressResDto("조회실패", BigDecimal.valueOf(0.0), BigDecimal.valueOf(0.0));
    }
}
