package com.msa.company_service.client;

import com.msa.company_service.dto.CoordinateDto;
import com.msa.company_service.global.CompanyErrorCode;
import com.msa.core_common.error.exception.CustomException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Component
public class HubClientFallbackFactory implements FallbackFactory<HubClient> {
    private static final Logger log = LoggerFactory.getLogger(HubClientFallbackFactory.class);

    @Override
    public HubClient create(Throwable cause) {
        return new HubClient() {
            @Override
            public Boolean getHubExist(UUID hubId) {
                log.error("event=COMPANY_HUB_EXIST_CALL_FAILED hubId={} reason={}", hubId, cause.getMessage());
                throw new CustomException(CompanyErrorCode.HUB_SERVICE_UNAVAILABLE);
            }

            @Override
            public CoordinateDto getCoordinates(String address) {
                log.error("event=COMPANY_HUB_COORDINATE_CALL_FAILED address={} reason={}", address, cause.getMessage());
                return new CoordinateDto(null, null);
            }
        };
    }
}
