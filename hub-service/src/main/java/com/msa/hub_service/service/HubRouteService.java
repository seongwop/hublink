package com.msa.hub_service.service;

import com.msa.core_common.error.exception.CustomException;
import com.msa.core_common.response.paging.PageRes;
import com.msa.hub_service.client.AddressGeocodingPort;
import com.msa.hub_service.client.CompanyClient;
import com.msa.hub_service.dto.*;
import com.msa.hub_service.entity.HubEntity;
import com.msa.hub_service.entity.HubRouteEntity;
import com.msa.hub_service.entity.RouteInfo;
import com.msa.hub_service.entity.RouteType;
import com.msa.hub_service.global.HubErrorCode;
import com.msa.hub_service.global.Util;
import com.msa.hub_service.message.HubCreatedEvent;
import com.msa.hub_service.message.HubDeletedEvent;
import com.msa.hub_service.message.HubUpdatedEvent;
import com.msa.hub_service.repository.HubRepository;
import com.msa.hub_service.repository.HubRouteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.msa.hub_service.global.Util.RouteCalculator.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class HubRouteService {

    private final HubRouteRepository hubRouteRepository;
    private final HubRepository hubRepository;
    private final AuditorAware<String> auditorAware;
    private final CompanyClient companyClient;
    private final AddressGeocodingPort geocodingPort;

    // 생성
    @Transactional
    public HubRouteResponse createHubRoute(UUID departureHubId, UUID arrivalHubId) {

        // 출발지 도착지 확인
        if (departureHubId.equals(arrivalHubId)) {
            throw new CustomException(HubErrorCode.SAME_HUB_NOT_ALLOWED);
        }

        //중복 경로 방지
        if (hubRouteRepository.existsByDepartureHub_HubIdAndArrivalHub_HubId(departureHubId, arrivalHubId)) {
            throw new CustomException(HubErrorCode.HUB_ROUTE_DUPLICATED);
        }

        // 허브 존재 확인
        HubEntity departureHub = hubRepository.findById(departureHubId)
                .orElseThrow(() -> new CustomException(HubErrorCode.HUB_NOT_FOUND));
        HubEntity arrivalHub = hubRepository.findById(arrivalHubId)
                .orElseThrow(() -> new CustomException(HubErrorCode.HUB_NOT_FOUND));

        // 허브 위치 확인
        if (departureHub.getLatitude() == null || departureHub.getLongitude() == null ||
                arrivalHub.getLatitude() == null || arrivalHub.getLongitude() == null) {
            throw new CustomException(HubErrorCode.NULL_COORDINATES);
        }

        HubRouteEntity hubRoute = HubRouteEntity.create(departureHub, arrivalHub);
        hubRoute = hubRouteRepository.save(hubRoute);

        return HubRouteResponse.from(hubRoute);

    }

    // 단건 조회
    @Cacheable(cacheNames = "hubRoute", key = "#hubRouteId.toString()")
    public HubRouteResponse getHubRoute(UUID hubRouteId) {
        HubRouteEntity hubRoute = hubRouteRepository.findById(hubRouteId)
                .orElseThrow(() -> new CustomException(HubErrorCode.HUB_ROUTE_NOT_FOUND));
        return HubRouteResponse.from(hubRoute);
    }

    // 수정(km, min)
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = "hubRoute", key = "#hubRouteId.toString()"),
            @CacheEvict(cacheNames = "hubPath", allEntries = true),
            @CacheEvict(cacheNames = "companyPath", allEntries = true)
    })
    public HubRouteResponse updateHubRoute(UUID hubRouteId, HubRouteUpdateRequest request) {
        HubRouteEntity hubRoute = hubRouteRepository.findById(hubRouteId)
                .orElseThrow(() -> new CustomException(HubErrorCode.HUB_ROUTE_NOT_FOUND));

        BigDecimal reqKm = request.estimatedDistanceKm();
        Integer reqMin = request.estimatedDurationMin();

        // 변경 내용 확인
        boolean isKmChanged = (reqKm != null) &&
                (hubRoute.getEstimatedDistanceKm() == null || reqKm.compareTo(hubRoute.getEstimatedDistanceKm()) != 0);

        boolean isMinChanged = (reqMin != null) &&
                (hubRoute.getEstimatedDurationMin() == null || !reqMin.equals(hubRoute.getEstimatedDurationMin()));

        // 변경 내용 없는 경우
        if (!isKmChanged && !isMinChanged) {
            return HubRouteResponse.from(hubRoute);
        }

        BigDecimal targetKm = hubRoute.getEstimatedDistanceKm();
        Integer targetMin = hubRoute.getEstimatedDurationMin();
        RouteType targetType = hubRoute.getRouteType();

        if (isKmChanged && isMinChanged) { // 모두 변경된 경우 - routeType만 자동 갱신
            targetKm = reqKm;
            targetMin = reqMin;
            targetType = determineRouteType(targetKm.doubleValue());

        } else if (isKmChanged) { // km만 변경된 경우 - 나머지 자동 갱신
            targetKm = reqKm;
            targetMin = calculateDuration(targetKm.doubleValue());
            targetType = determineRouteType(targetKm.doubleValue());

        } else { // min만 변경된 경우 - min만 갱신
            targetMin = reqMin;
        }

        // 업데이트
        hubRoute.update(targetKm, targetMin, targetType);

        return HubRouteResponse.from(hubRoute);
    }

    // 삭제
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = "hubRoute", key = "#hubRouteId.toString()"),
            @CacheEvict(cacheNames = "hubPath", allEntries = true),
            @CacheEvict(cacheNames = "companyPath", allEntries = true)
    })
    public HubRouteResponse deleteHubRoute(UUID hubRouteId) {
        HubRouteEntity hubRoute = hubRouteRepository.findById(hubRouteId)
                .orElseThrow(() -> new CustomException(HubErrorCode.HUB_ROUTE_NOT_FOUND));

        String deletedBy = auditorAware.getCurrentAuditor().orElse("SYSTEM");
        hubRoute.delete(deletedBy);
        return HubRouteResponse.from(hubRoute);
    }

    // 출발/도착/루트로 검색
    public PageRes<HubRouteResponse> getHubRoutes(UUID departureHubId, UUID arrivalHubId, RouteType routeType, Pageable pageable) {

        Page<HubRouteEntity> hubRoutePage = hubRouteRepository.searchHubRoutes(departureHubId, arrivalHubId, routeType, pageable);

        return new PageRes<>(hubRoutePage.map(HubRouteResponse::from));
    }

    // 경로 검색
    @Cacheable(cacheNames = "hubPath",
            key = "#departureHubId.toString() + '_' + #arrivalHubId.toString() + '_' + (#companyId != null ? #companyId.toString() : 'null')"
    )
    public List<HubRouteResponse> getHubPath(UUID departureHubId, UUID arrivalHubId, UUID companyId) {
        List<HubRouteResponse> routeList = new ArrayList<>();

        HubRouteEntity directRoute = hubRouteRepository.findByDepartureHub_HubIdAndArrivalHub_HubId(departureHubId, arrivalHubId)
                .orElseThrow(() -> new CustomException(HubErrorCode.HUB_ROUTE_NOT_FOUND));

        BigDecimal directDistance = directRoute.getEstimatedDistanceKm();

        // 거리 200 미만
        if (directRoute.getRouteType() == RouteType.P2P) {
            routeList.add(convertToResponse(directRoute, 1));
        } else {
            // 거리 200 이상
            List<HubRouteEntity> transitRoutes = hubRouteRepository.findOptimalTransitRoute(departureHubId, arrivalHubId, directDistance);
            // 최적 경로 없을 때
            if (transitRoutes.isEmpty()) {
                routeList.add(convertToResponse(directRoute, 1));
            } else {
                // 최적 경로 있을 때
                routeList.add(convertToResponse(transitRoutes.get(0), 1));
                routeList.add(convertToResponse(transitRoutes.get(1), 2));
            }
        }

        if(companyId!=null){
            int lastSequence = routeList.size()+1;
            HubEntity arrivalHub = directRoute.getArrivalHub();

            HubRouteResponse lastMileRoute = createLastMileRoute(arrivalHub, companyId, lastSequence);
            routeList.add(lastMileRoute);
        }

        return routeList;
    }

    // 업체-업체 경로
    @Cacheable(cacheNames = "companyPath", key = "#departureCompanyId.toString() + '_' + #arrivalCompanyId.toString()")
    public List<HubRouteResponse> getCompanyToCompanyPath(UUID departureCompanyId, UUID arrivalCompanyId){
        CompanyDto departureCompany = companyClient.getCompanyLocation(departureCompanyId);

        UUID departureHubId = departureCompany.hubId();

        if (departureHubId == null) {
            throw new CustomException(HubErrorCode.HUB_NOT_FOUND);
        }

        CompanyDto arrivalCompany = companyClient.getCompanyLocation(arrivalCompanyId);
        BigDecimal[] arrivalCoords = getValidCoordinates(arrivalCompany);

        List<HubEntity> allHubs = hubRepository.findAll();
        UUID arrivalHubId = findClosestHub(allHubs, arrivalCoords[0], arrivalCoords[1]);

        // 이름 추가 전
        List<HubRouteResponse> rawPath = getHubPath(departureHubId, arrivalHubId, arrivalCompanyId);

        // 이름 추가 후 리턴
        return enrichRouteNames(rawPath, allHubs, arrivalCompanyId);
    }

    // 자동 생성
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @CacheEvict(cacheNames = {"hubRoute", "hubPath", "companyPath"}, allEntries = true)
    public void createRoutesForNewHub(HubCreatedEvent event) {
        HubEntity newHub = hubRepository.findById(event.hubId())
                .orElseThrow(() -> new CustomException(HubErrorCode.HUB_NOT_FOUND));

        List<HubEntity> existingHubs = hubRepository.findByHubIdNot(newHub.getHubId());

        List<HubEntity> validExistingHubs = existingHubs.stream()
                .filter(hub -> hub.getLatitude() != null && hub.getLongitude() != null)
                .toList();


        if (validExistingHubs.isEmpty()) {
            return;
        }

        // 이미 연결되어 있을 경우
        List<HubRouteEntity> existingRoutes = hubRouteRepository.findByInvolvedHubId(newHub.getHubId());
        // 중복 검사
        Set<String> existingRouteSet = existingRoutes.stream()
                .map(route -> route.getDepartureHub().getHubId().toString() + "_" + route.getArrivalHub().getHubId().toString())
                .collect(Collectors.toSet());

        List<HubRouteEntity> newRoutes = new ArrayList<>();

        for (HubEntity existingHub : validExistingHubs) {
            String forwardKey = newHub.getHubId().toString() + "_" + existingHub.getHubId().toString();
            String backwardKey = existingHub.getHubId().toString() + "_" + newHub.getHubId().toString();

            // 중복 아닐 때만 생성
            if (!existingRouteSet.contains(forwardKey)) {
                newRoutes.add(HubRouteEntity.create(newHub, existingHub));
            }
            if (!existingRouteSet.contains(backwardKey)) {
                newRoutes.add(HubRouteEntity.create(existingHub, newHub));
            }
        }

        if (!newRoutes.isEmpty()) {
            hubRouteRepository.saveAll(newRoutes);
        }
    }

    // 자동 수정
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @CacheEvict(cacheNames = {"hubRoute", "hubPath", "companyPath"}, allEntries = true)
    public void updateRoutesForUpdatedHub(HubUpdatedEvent event) {
        List<HubRouteEntity> affectedRoutes = hubRouteRepository.findByInvolvedHubId(event.hubId());

        if (affectedRoutes.isEmpty()) {
            //기존 경로가 없지만 수정된 경우
            createRoutesForNewHub(new HubCreatedEvent(event.hubId()));
            return;
        }

        for (HubRouteEntity route : affectedRoutes) {
            route.recalculateRouteInfo();
        }

        hubRouteRepository.saveAll(affectedRoutes);
    }

    // 자동 삭제
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @CacheEvict(cacheNames = {"hubRoute", "hubPath", "companyPath"}, allEntries = true)
    public void deleteRoutesForDeletedHub(HubDeletedEvent event) {
        List<HubRouteEntity> affectedRoutes = hubRouteRepository.findByInvolvedHubId(event.hubId());

        if (affectedRoutes.isEmpty()) {
            return;
        }

        String deletedBy = auditorAware.getCurrentAuditor().orElse("SYSTEM");

        for (HubRouteEntity route : affectedRoutes) {
            route.delete(deletedBy);
        }
    }

    // 이름 설정
    private List<HubRouteResponse> enrichRouteNames(List<HubRouteResponse> rawPath, List<HubEntity> allHubs, UUID arrivalCompanyId) {
        if (rawPath == null || rawPath.isEmpty()) {
            return rawPath;
        }

        Map<UUID, String> hubNameMap = allHubs.stream()
                .collect(Collectors.toMap(HubEntity::getHubId, HubEntity::getName));

        // 업체 이름
        String arrivalCompanyName = null;
        try {
            List<CompanyNameResponse> companyNames = companyClient.getCompanyNames(List.of(arrivalCompanyId));
            if (companyNames != null && !companyNames.isEmpty()) {
                arrivalCompanyName = companyNames.get(0).name();
            }
        } catch (Exception e) {
            log.error("업체 이름을 가져오는데 실패했습니다. companyId: {}", arrivalCompanyId, e);
        }

        final String finalArrivalCompanyName = arrivalCompanyName;

        return rawPath.stream()
                .map(route -> new HubRouteResponse(
                        route.hubRouteId(),
                        route.departureHub(),
                        route.arrivalHub(),
                        route.arrivalCompanyId(),
                        hubNameMap.get(route.departureHub()),
                        hubNameMap.get(route.arrivalHub()),
                        route.arrivalCompanyId() != null ? finalArrivalCompanyName : null,
                        route.estimatedDistanceKm(),
                        route.estimatedDurationMin(),
                        route.routeType(),
                        route.sequence()
                ))
                .collect(Collectors.toList());
    }

    // 컴퍼니 주소랑 가까운 허브 찾기
    private UUID findClosestHub(List<HubEntity> hubs, BigDecimal targetLat, BigDecimal targetLon) {
        UUID closestHubId = null;
        double minDistance = Double.MAX_VALUE;

        for (HubEntity hub : hubs) {
            if (hub.getLatitude() == null || hub.getLongitude() == null) continue;

            double distance = Util.DistanceCalculator.getDistance(
                    hub.getLatitude(), hub.getLongitude(),
                    targetLat, targetLon
            );

            if (distance < minDistance) {
                minDistance = distance;
                closestHubId = hub.getHubId();
            }
        }

        if (closestHubId == null) {
            throw new CustomException(HubErrorCode.HUB_NOT_FOUND);
        }
        return closestHubId;
    }


    // 도착 허브에서 도착 컴퍼니 까지 루트 생성
    private HubRouteResponse createLastMileRoute(HubEntity arrivalHub, UUID companyId, int sequence) {

        CompanyDto company = companyClient.getCompanyLocation(companyId);

        BigDecimal[] arrivalCoords = getValidCoordinates(company);

        RouteInfo calcResult = calculate(
                arrivalHub.getLatitude(),
                arrivalHub.getLongitude(),
                arrivalCoords[0],
                arrivalCoords[1]
        );

        return new HubRouteResponse(
                null,
                arrivalHub.getHubId(),
                null,
                companyId,
                null,
                null,
                null,
                calcResult.distanceKm(),
                calcResult.durationMin(),
                RouteType.P2P,
                sequence
        );
    }

    // 위도 경도 있는지 검사
    private BigDecimal[] getValidCoordinates(CompanyDto company) {
        BigDecimal targetLat = company.latitude();
        BigDecimal targetLon = company.longitude();

        if (targetLat == null || targetLon == null) {
            try {
                CoordinateDto coordinate = geocodingPort.getCoordinate(company.address());
                targetLat = coordinate.latitude();
                targetLon = coordinate.longitude();
            } catch (Exception e) {
                throw new CustomException(HubErrorCode.NULL_COORDINATES); // 좌표 복구 실패 시 예외 처리
            }
        }
        return new BigDecimal[]{targetLat, targetLon};
    }

    // 루트 순서 추가
    private HubRouteResponse convertToResponse(HubRouteEntity entity, int sequence) {
        return new HubRouteResponse(
                entity.getHubRouteId(),
                entity.getDepartureHub().getHubId(),
                entity.getArrivalHub().getHubId(),
                null,
                null,
                null,
                null,
                entity.getEstimatedDistanceKm(),
                entity.getEstimatedDurationMin(),
                entity.getRouteType(),
                sequence
        );
    }
}