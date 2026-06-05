package com.msa.hub_service.service;

import com.msa.core_common.error.exception.CustomException;
import com.msa.core_common.response.paging.PageRes;
import com.msa.hub_service.client.AddressGeocodingPort;
import com.msa.hub_service.dto.CoordinateDto;
import com.msa.hub_service.dto.HubRequest;
import com.msa.hub_service.dto.HubResponse;
import com.msa.hub_service.entity.HubEntity;
import com.msa.hub_service.global.HubErrorCode;
import com.msa.hub_service.message.HubCreatedEvent;
import com.msa.hub_service.message.HubDeletedEvent;
import com.msa.hub_service.message.HubUpdatedEvent;
import com.msa.hub_service.repository.HubRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class HubService {
    private final HubRepository hubRepository;
    private final AddressGeocodingPort geocodingPort;
    private final AuditorAware<String> auditorAware;
    private final ApplicationEventPublisher eventPublisher;

    // Hub 생성
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public HubResponse createHub(String name, String address, BigDecimal latitude, BigDecimal longitude) {

        validateDuplicateHub(name, address);

        BigDecimal finalLat = latitude;
        BigDecimal finalLon = longitude;

        if (finalLat == null || finalLon == null) {
            CoordinateDto coordinate = getCoordinate(address);
            finalLat = coordinate.latitude();
            finalLon = coordinate.longitude();
        }

        HubEntity hub = HubEntity.create(name, address, finalLat, finalLon);

        hubRepository.save(hub);

        if (hub.getLatitude() != null && hub.getLongitude() != null) {
            eventPublisher.publishEvent(new HubCreatedEvent(hub.getHubId()));
        }

        return HubResponse.from(hub);
    }

    // Hub 상세 조회
    @Cacheable(value = "hub", key = "#hubId")
    public HubResponse getHub(UUID hubId) {
        HubEntity hub = hubRepository.findById(hubId)
                .orElseThrow(() -> new CustomException(HubErrorCode.HUB_NOT_FOUND));
        return HubResponse.from(hub);
    }

    // Hub 이름/주소 변경
    @CachePut(value = "hub", key = "#hubId")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public HubResponse updateHub(UUID hubId, HubRequest request) {

        HubEntity hub = hubRepository.findById(hubId)
                .orElseThrow(() -> new CustomException(HubErrorCode.HUB_NOT_FOUND));

        boolean isAddressChanged = !hub.getAddress().equals(request.address());

        // 바뀐 이름/주소 이미 등록되어 있는지 확인
        if (!hub.getName().equals(request.name()) && hubRepository.existsByName(request.name())) {
            throw new CustomException(HubErrorCode.HUB_NAME_DUPLICATED);
        }
        if (isAddressChanged && hubRepository.existsByAddress(request.address())) {
            throw new CustomException(HubErrorCode.HUB_ADDRESS_DUPLICATED);
        }

        // 수정하려고 보낸 위도/경도
        BigDecimal targetLat = request.latitude();
        BigDecimal targetLon = request.longitude();

        // 주소 값 변경되었을 때 대처 - 1. 보낸 위도/경도 2. 주소에 따른 api 호출
        if (isAddressChanged) {
            if (targetLat == null || targetLon == null) {
                CoordinateDto coordinate = getCoordinate(request.address());

                if (coordinate.latitude() == null || coordinate.longitude() == null) {
                    throw new CustomException(HubErrorCode.GEOCODING_FAILED);
                }
                targetLat = coordinate.latitude();
                targetLon = coordinate.longitude();
            }
        } else {
            targetLat = (targetLat != null) ? targetLat : hub.getLatitude();
            targetLon = (targetLon != null) ? targetLon : hub.getLongitude();
        }

        // 위도 경도 변경 확인 - route 변경하기 위함
        boolean isCoordinateChanged = false;
        if (targetLat != null && targetLon != null) {
            if (hub.getLatitude() == null || hub.getLongitude() == null) {
                isCoordinateChanged = true;
            } else {
                isCoordinateChanged = (targetLat.compareTo(hub.getLatitude()) != 0) ||
                        (targetLon.compareTo(hub.getLongitude()) != 0);
            }
        }

        hub.updateHub(request.name(), request.address(), targetLat, targetLon);
        hubRepository.save(hub);

        if (isCoordinateChanged) {
            eventPublisher.publishEvent(new HubUpdatedEvent(hub.getHubId()));
        }

        return HubResponse.from(hub);
    }

    // 허브 검색
    public PageRes<HubResponse> getHubs(String name, Pageable pageable) {
        Page<HubEntity> hubPage;

        if (StringUtils.hasText(name)) {
            hubPage = hubRepository.findByNameContaining(name, pageable);
        } else {
            hubPage = hubRepository.findAll(pageable);
        }

        return new PageRes<>(hubPage.map(HubResponse::from));
    }

    // 허브 삭제
    @CacheEvict(value = "hub", key = "#hubId")
    @Transactional
    public HubResponse deleteHub(UUID hubId) {
        HubEntity hub = hubRepository.findById(hubId)
                .orElseThrow(() -> new CustomException(HubErrorCode.HUB_NOT_FOUND));
        String deletedBy = auditorAware.getCurrentAuditor().orElse("SYSTEM");
        hub.delete(deletedBy);
        eventPublisher.publishEvent(new HubDeletedEvent(hubId));
        return HubResponse.from(hub);
    }

    // api로 위도와 경도 구하기
    public CoordinateDto getCoordinate(String address) {
        try {
            return geocodingPort.getCoordinate(address);
        } catch (Exception e) {
            log.warn("외부 API 장애로 인해 좌표 없이 허브를 저장합니다. address: {}, exception: {}", address, e.toString());
            return new CoordinateDto(null, null);
        }
    }

    //  허브 존재 확인
    public boolean getHubExist(UUID hubId) {
        return hubRepository.existsById(hubId);
    }

    // 중복 hub 등록 방지
    private void validateDuplicateHub(String name, String address) {
        if (hubRepository.existsByName(name)) {
            throw new CustomException(HubErrorCode.HUB_NAME_DUPLICATED);
        }
        if (hubRepository.existsByAddress(address)) {
            throw new CustomException(HubErrorCode.HUB_ADDRESS_DUPLICATED);
        }
    }
}