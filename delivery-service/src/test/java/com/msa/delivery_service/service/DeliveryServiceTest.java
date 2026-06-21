package com.msa.delivery_service.service;

import com.msa.delivery_service.client.DeliveryExternalService;
import com.msa.delivery_service.client.hub.HubClient;
import com.msa.delivery_service.client.hub.dto.HubRouteResponse;
import com.msa.delivery_service.client.user.UserClient;
import com.msa.delivery_service.enums.DeliveryAssignmentType;
import com.msa.delivery_service.enums.DeliveryLocationType;
import com.msa.delivery_service.enums.DeliveryRouteStatus;
import com.msa.delivery_service.enums.DeliveryRouteType;
import com.msa.delivery_service.enums.DeliveryStatus;
import com.msa.delivery_service.client.user.dto.DeliveryManagerResponse;
import com.msa.delivery_service.dto.DeliveryRequest;
import com.msa.delivery_service.dto.DeliveryResponse;
import com.msa.delivery_service.dto.DeliveryStatusUpdateRequest;
import com.msa.delivery_service.client.user.dto.HubManagerResponse;
import com.msa.delivery_service.entity.Delivery;
import com.msa.delivery_service.entity.DeliveryRouteHistory;
import com.msa.delivery_service.message.RedisStreamEventPublisher;
import com.msa.delivery_service.repository.DeliveryAssignmentCountRepository;
import com.msa.delivery_service.repository.DeliveryRepository;
import com.msa.delivery_service.repository.DeliveryRouteHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryServiceTest {

    @Mock
    private DeliveryRepository deliveryRepository;

    @Mock
    private DeliveryRouteHistoryRepository deliveryRouteHistoryRepository;

    @Mock
    private HubClient hubClient;

    @Mock
    private UserClient userClient;

    @Mock
    private DeliveryAssignmentLockService deliveryAssignmentLockService;

    @Mock
    private RedisStreamEventPublisher redisStreamEventPublisher;

    @Mock
    private DeliveryOutboxService deliveryOutboxService;

    @Mock
    private DeliveryAssignmentCountRepository deliveryAssignmentCountRepository;

    private DeliveryService deliveryService;

    @BeforeEach
    void setUp() {
        DeliveryAssignmentCountService deliveryAssignmentCountService =
                new DeliveryAssignmentCountService(deliveryAssignmentCountRepository);
        DeliveryCreateService deliveryCreateService = new DeliveryCreateService(
                deliveryRepository,
                deliveryRouteHistoryRepository,
                redisStreamEventPublisher,
                deliveryOutboxService,
                deliveryAssignmentCountService
        );
        DeliveryExternalService deliveryExternalService = new DeliveryExternalService(hubClient, userClient);
        deliveryService = new DeliveryService(
                deliveryRepository,
                deliveryRouteHistoryRepository,
                deliveryExternalService,
                deliveryCreateService,
                deliveryAssignmentLockService,
                deliveryAssignmentCountService
        );
        ReflectionTestUtils.setField(deliveryService, "maxActiveAssignmentsPerManager", 30);
    }

    @Test
    @DisplayName("생성: 배송과 경로 저장 검증")
    void createDelivery() {
        // given
        UUID orderId = UUID.randomUUID();
        UUID supplierCompanyId = UUID.randomUUID();
        UUID receiverCompanyId = UUID.randomUUID();
        UUID firstHubId = UUID.randomUUID();
        UUID secondHubId = UUID.randomUUID();
        UUID finalHubId = UUID.randomUUID();
        UUID companyManagerId = UUID.randomUUID();
        UUID firstHubManagerId = UUID.randomUUID();
        UUID secondHubManagerId = UUID.randomUUID();
        DeliveryRequest request = createDeliveryRequest(orderId, supplierCompanyId, receiverCompanyId);
        HubManagerResponse hubManager = createHubManagerResponse(firstHubId);
        HubRouteResponse firstRoute = createHubRouteResponse(UUID.randomUUID(), firstHubId, secondHubId, null, 1, 20);
        HubRouteResponse secondRoute = createHubRouteResponse(UUID.randomUUID(), secondHubId, finalHubId, null, 2, 15);
        HubRouteResponse lastRoute = createHubRouteResponse(UUID.randomUUID(), finalHubId, null, receiverCompanyId, 3, 25);
        DeliveryManagerResponse companyManager = createDeliveryManagerResponse(
                companyManagerId,
                finalHubId,
                "COMPANY_DELIVERY",
                1
        );
        DeliveryManagerResponse firstHubManager = createDeliveryManagerResponse(
                firstHubManagerId,
                firstHubId,
                "HUB_DELIVERY",
                1
        );
        DeliveryManagerResponse secondHubManager = createDeliveryManagerResponse(
                secondHubManagerId,
                secondHubId,
                "HUB_DELIVERY",
                1
        );

        when(deliveryRepository.existsByOrderId(orderId)).thenReturn(false);
        when(hubClient.getRoutes(supplierCompanyId, receiverCompanyId)).thenReturn(List.of(firstRoute, secondRoute, lastRoute));
        when(userClient.getHubManager(firstHubId)).thenReturn(hubManager);
        when(userClient.getDeliveryManagers(anyList())).thenReturn(List.of(companyManager, firstHubManager, secondHubManager));
        when(deliveryAssignmentCountRepository.findAssignmentCountsByManagerIds(
                eq(List.of(companyManagerId)),
                eq(DeliveryAssignmentType.COMPANY_DELIVERY)
        ))
                .thenReturn(List.of());
        when(deliveryAssignmentCountRepository.findAssignmentCountsByManagerIds(
                eq(List.of(firstHubManagerId, secondHubManagerId)),
                eq(DeliveryAssignmentType.HUB_DELIVERY)
        ))
                .thenReturn(List.of());
        when(deliveryAssignmentLockService.executeWithLocks(anyList(), any()))
                .thenAnswer(invocation -> {
                    Supplier<?> supplier = invocation.getArgument(1);
                    return supplier.get();
                });
        when(deliveryRepository.save(any(Delivery.class))).thenAnswer(invocation -> {
            Delivery delivery = invocation.getArgument(0);
            ReflectionTestUtils.setField(delivery, "deliveryId", UUID.randomUUID());
            return delivery;
        });

        // when
        DeliveryResponse response = deliveryService.createDelivery(request);

        // then

        // 배송 저장 검증
        ArgumentCaptor<Delivery> deliveryCaptor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryRepository).save(deliveryCaptor.capture());
        Delivery savedDelivery = deliveryCaptor.getValue();
        assertThat(response.getOrderId()).isEqualTo(orderId);
        assertThat(savedDelivery.getDepartureHubId()).isEqualTo(firstHubId);
        assertThat(savedDelivery.getDestinationHubId()).isEqualTo(finalHubId);
        assertThat(savedDelivery.getCompanyDeliveryManagerId()).isEqualTo(companyManagerId);

        // 경로 저장 검증
        ArgumentCaptor<List<DeliveryRouteHistory>> routeCaptor = ArgumentCaptor.forClass(List.class);
        verify(deliveryRouteHistoryRepository).saveAll(routeCaptor.capture());
        assertThat(routeCaptor.getValue()).extracting(DeliveryRouteHistory::getDeliveryManagerId)
                .containsExactly(firstHubManagerId, secondHubManagerId, companyManagerId);
        assertThat(routeCaptor.getValue()).extracting(DeliveryRouteHistory::getRouteType)
                .containsExactly(
                        DeliveryRouteType.HUB_TO_HUB,
                        DeliveryRouteType.HUB_TO_HUB,
                        DeliveryRouteType.HUB_TO_COMPANY
                );
        verify(deliveryAssignmentCountRepository).increaseAssignmentCounts(
                eq(Map.of(companyManagerId, 1L)),
                eq(DeliveryAssignmentType.COMPANY_DELIVERY)
        );
        verify(deliveryAssignmentCountRepository).increaseAssignmentCounts(
                eq(Map.of(firstHubManagerId, 1L, secondHubManagerId, 1L)),
                eq(DeliveryAssignmentType.HUB_DELIVERY)
        );
    }

    @Test
    @DisplayName("조회: 주문 ID 조회 검증")
    void getDeliveryByOrderId() {
        // given
        UUID orderId = UUID.randomUUID();
        Delivery delivery = createDeliveryEntity(UUID.randomUUID(), UUID.randomUUID());
        ReflectionTestUtils.setField(delivery, "orderId", orderId);
        when(deliveryRepository.findByOrderId(orderId)).thenReturn(Optional.of(delivery));

        // when
        DeliveryResponse response = deliveryService.getDeliveryByOrderId("MASTER", orderId);

        // then

        // 주문 ID 매핑 검증
        assertThat(response.getOrderId()).isEqualTo(orderId);
        assertThat(response.getDeliveryId()).isEqualTo(delivery.getDeliveryId());
    }

    @Test
    @DisplayName("업데이트: 배송 완료 적용")
    void updateDeliveryStatus() {
        // given
        UUID deliveryManagerId = UUID.randomUUID();
        Delivery delivery = createDeliveryEntity(UUID.randomUUID(), deliveryManagerId);
        delivery.updateStatus(DeliveryStatus.HUB_IN_TRANSIT);
        delivery.updateStatus(DeliveryStatus.DESTINATION_HUB_ARRIVED);
        delivery.updateStatus(DeliveryStatus.OUT_FOR_DELIVERY);
        DeliveryStatusUpdateRequest request = instantiate(DeliveryStatusUpdateRequest.class);
        ReflectionTestUtils.setField(request, "status", DeliveryStatus.DELIVERED);
        when(deliveryRepository.findById(delivery.getDeliveryId())).thenReturn(Optional.of(delivery));

        // when
        DeliveryResponse response = deliveryService.updateDeliveryStatus(
                deliveryManagerId,
                "DELIVERY_MANAGER",
                delivery.getDeliveryId(),
                request
        );

        // then

        // 배송 완료 상태 검증
        assertThat(response.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(delivery.getDeliveredAt()).isNotNull();
        verify(deliveryAssignmentCountRepository).decreaseAssignmentCount(
                eq(deliveryManagerId),
                eq(DeliveryAssignmentType.COMPANY_DELIVERY.name()),
                eq(1L)
        );
        verify(deliveryRepository).flush();
    }

    @Test
    @DisplayName("보상: 배송과 경로 soft delete 적용")
    void compensateDeliveryCreation() {
        // given
        UUID orderId = UUID.randomUUID();
        Delivery delivery = createDeliveryEntity(UUID.randomUUID(), UUID.randomUUID());
        ReflectionTestUtils.setField(delivery, "orderId", orderId);
        DeliveryRouteHistory routeHistory = createRouteHistoryEntity(delivery, UUID.randomUUID());
        when(deliveryRepository.findByOrderId(orderId)).thenReturn(Optional.of(delivery));
        when(deliveryRouteHistoryRepository.findByDeliveryDeliveryIdOrderBySequenceAsc(delivery.getDeliveryId()))
                .thenReturn(List.of(routeHistory));

        // when
        deliveryService.compensateDeliveryCreation(orderId);

        // the
        // 배송 soft delete 검증
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.CANCELLED);
        assertThat(delivery.getDeletedAt()).isNotNull();

        // 경로 soft delete 검증
        assertThat(routeHistory.getStatus()).isEqualTo(DeliveryRouteStatus.FAILED);
        assertThat(routeHistory.getDeletedAt()).isNotNull();
        verify(deliveryAssignmentCountRepository).decreaseAssignmentCount(
                eq(delivery.getCompanyDeliveryManagerId()),
                eq(DeliveryAssignmentType.COMPANY_DELIVERY.name()),
                eq(1L)
        );
        verify(deliveryAssignmentCountRepository).decreaseAssignmentCount(
                eq(routeHistory.getDeliveryManagerId()),
                eq(DeliveryAssignmentType.HUB_DELIVERY.name()),
                eq(1L)
        );
        verify(deliveryRouteHistoryRepository).flush();
        verify(deliveryRepository).flush();
    }

    private DeliveryRequest createDeliveryRequest(UUID orderId, UUID supplierCompanyId, UUID receiverCompanyId) {
        DeliveryRequest request = instantiate(DeliveryRequest.class);
        ReflectionTestUtils.setField(request, "orderId", orderId);
        ReflectionTestUtils.setField(request, "supplyCompanyId", supplierCompanyId);
        ReflectionTestUtils.setField(request, "receiverCompanyId", receiverCompanyId);
        ReflectionTestUtils.setField(request, "deliveryAddress", "Seoul");
        ReflectionTestUtils.setField(request, "receiverName", "receiver");
        return request;
    }

    private Delivery createDeliveryEntity(UUID deliveryId, UUID companyDeliveryManagerId) {
        Delivery delivery = Delivery.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                companyDeliveryManagerId,
                "Seoul",
                "receiver",
                "slack-id"
        );
        ReflectionTestUtils.setField(delivery, "deliveryId", deliveryId);
        return delivery;
    }

    private DeliveryRouteHistory createRouteHistoryEntity(Delivery delivery, UUID deliveryManagerId) {
        DeliveryRouteHistory routeHistory = DeliveryRouteHistory.create(
                delivery,
                deliveryManagerId,
                1,
                DeliveryRouteType.HUB_TO_HUB,
                DeliveryLocationType.HUB,
                UUID.randomUUID(),
                DeliveryLocationType.HUB,
                UUID.randomUUID(),
                null,
                null
        );
        ReflectionTestUtils.setField(routeHistory, "deliveryRouteHistoryId", UUID.randomUUID());
        return routeHistory;
    }

    private HubManagerResponse createHubManagerResponse(UUID hubId) {
        HubManagerResponse response = instantiate(HubManagerResponse.class);
        ReflectionTestUtils.setField(response, "hubManagerId", UUID.randomUUID());
        ReflectionTestUtils.setField(response, "hubId", hubId);
        ReflectionTestUtils.setField(response, "hubManagerSlackId", "hub-manager");
        return response;
    }

    private HubRouteResponse createHubRouteResponse(
            UUID hubRouteId,
            UUID departureHubId,
            UUID arrivalHubId,
            UUID arrivalCompanyId,
            int sequence,
            int estimatedDurationMin
    ) {
        HubRouteResponse response = instantiate(HubRouteResponse.class);
        ReflectionTestUtils.setField(response, "hubRouteId", hubRouteId);
        ReflectionTestUtils.setField(response, "departureHubId", departureHubId);
        ReflectionTestUtils.setField(response, "arrivalHubId", arrivalHubId);
        ReflectionTestUtils.setField(response, "arrivalCompanyId", arrivalCompanyId);
        ReflectionTestUtils.setField(response, "sequence", sequence);
        ReflectionTestUtils.setField(response, "estimatedDurationMin", estimatedDurationMin);
        ReflectionTestUtils.setField(response, "routeType", "P2P");
        return response;
    }

    private DeliveryManagerResponse createDeliveryManagerResponse(
            UUID deliveryManagerId,
            UUID hubId,
            String type,
            int deliverySequence
    ) {
        DeliveryManagerResponse response = instantiate(DeliveryManagerResponse.class);
        ReflectionTestUtils.setField(response, "deliveryManagerId", deliveryManagerId);
        ReflectionTestUtils.setField(response, "hubId", hubId);
        ReflectionTestUtils.setField(response, "type", type);
        ReflectionTestUtils.setField(response, "deliverySequence", deliverySequence);
        return response;
    }

    private <T> T instantiate(Class<T> type) {
        try {
            Constructor<T> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
