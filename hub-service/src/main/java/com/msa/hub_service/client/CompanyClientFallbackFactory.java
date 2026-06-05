package com.msa.hub_service.client;

import com.msa.core_common.error.exception.CustomException;
import com.msa.hub_service.dto.CompanyDto;
import com.msa.hub_service.dto.CompanyNameResponse;
import com.msa.hub_service.global.HubErrorCode;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CompanyClientFallbackFactory implements FallbackFactory<CompanyClient> {
    @Override
    public CompanyClient create(Throwable cause) {
        return new CompanyClient() {
            @Override
            public CompanyDto getCompanyLocation(UUID companyId) {
                throw new CustomException(HubErrorCode.COMPANY_SERVICE_UNAVAILABLE);
            }

            @Override
            public List<CompanyNameResponse> getCompanyNames(List<UUID> companyIds) {
                log.error("event=HUB_COMPANY_NAMES_CALL_FAILED companyIds={}", companyIds);
                return java.util.Collections.emptyList();
            }
        };
    }
}
