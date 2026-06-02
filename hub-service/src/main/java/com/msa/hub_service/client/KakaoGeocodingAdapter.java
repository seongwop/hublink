package com.msa.hub_service.client;

import com.msa.core_common.error.exception.CustomException;
import com.msa.hub_service.dto.CoordinateDto;
import com.msa.hub_service.dto.KakaoAddressResponse;
import com.msa.hub_service.global.HubErrorCode;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

@Slf4j
@Component
public class KakaoGeocodingAdapter implements AddressGeocodingPort {

    private final RestClient restClient;
    private final String kakaoRestApiKey;
    private final boolean kakaoEnabled;

    public KakaoGeocodingAdapter(
            RestClient.Builder restClientBuilder,
            @Value("${kakao.rest-api-key}") String kakaoRestApiKey,
            @Value("${kakao.base-url}") String baseUrl,
            @Value("${kakao.enabled:false}") boolean kakaoEnabled
    ) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.kakaoRestApiKey = kakaoRestApiKey;
        this.kakaoEnabled = kakaoEnabled;
    }

    @Override
    @RateLimiter(name = "kakaoApi")
    @Retry(name = "kakaoApi", fallbackMethod = "fallbackCoordinate")
    public CoordinateDto getCoordinate(String address) {
        // Kakao enabled 설정이 false일 때 진행되는 로직
        if (!kakaoEnabled) {
            log.info("Kakao 비활성화로 좌표 API 호출 생략 address={}", address);
            return new CoordinateDto(null, null);
        }

        // 1. RestClient를 이용한 API 호출
        KakaoAddressResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v2/local/search/address.json")
                        .queryParam("query", address)
                        .build())
                .header("Authorization", "KakaoAK " + kakaoRestApiKey)
                .retrieve()
                .body(KakaoAddressResponse.class); // 정의한 DTO로 자동 매핑

        // 2. 응답 검증 (결과가 없는 경우)
        if (response == null || response.documents() == null || response.documents().isEmpty()) {
            throw new CustomException(HubErrorCode.ADDRESS_NOT_FOUND);
        }

        // 3. String으로 받은 위경도를 BigDecimal로 변환
        KakaoAddressResponse.Document document = response.documents().get(0);
        BigDecimal longitude = new BigDecimal(document.longitude());
        BigDecimal latitude = new BigDecimal(document.latitude());

        // 4. 공통 DTO로 반환
        return new CoordinateDto(latitude, longitude);
    }

    private CoordinateDto fallbackCoordinate(String address, Exception e) {

        log.error("[Fallback] 카카오 API 호출 최종 실패. 주소: {}, 사유: {}", address, e.getMessage());
        throw new CustomException(HubErrorCode.COORDINATE_API_ERROR);

    }
}
