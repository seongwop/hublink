package com.msa.order_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.core_common.error.exception.CustomException;
import com.msa.core_common.response.paging.PageRes;
import com.msa.order_service.dto.req.MakeDeliveryReqDto;
import com.msa.order_service.dto.req.ModifyStockReqDto;
import com.msa.order_service.dto.req.OrderMakeReqDto;
import com.msa.order_service.dto.res.*;
import com.msa.order_service.entity.OrderItems;
import com.msa.order_service.entity.Orders;
import com.msa.order_service.entity.Outbox;
import com.msa.order_service.error.OrderErrorCode;
import com.msa.order_service.client.circuit.DeliveryCircuitService;
import com.msa.order_service.client.circuit.ProductCircuitService;
import com.msa.order_service.repository.OrderJpaRepository;
import com.msa.order_service.client.circuit.CompanyCircuitService;
import com.msa.order_service.client.circuit.UserCircuitService;
import com.msa.order_service.repository.OutboxRepository;
import com.msa.order_service.type.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderJpaRepository orderJpaRepository;
    private final UserCircuitService userCircuitService;
    private final CompanyCircuitService companyCircuitService;
    private final ProductCircuitService productCircuitService;
    private final DeliveryCircuitService deliveryCircuitService;
    private final RedisTemplate<String, String> redisTemplate;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public PageRes<UserOrderResDto> getOrders(UUID loginUserId, Status status, Pageable pageable) {

        pageable = getPageable(pageable);

        List<UsernameResDto> loginUserList = userCircuitService.getUserNames(List.of(loginUserId));

        if (loginUserList.isEmpty()) {
            return new PageRes<>(Page.empty(pageable));
        }

        // 내 소속 회사 ID 추출
        UUID myCompanyId = loginUserList.get(0).companyId();

        Page<Orders> all = orderJpaRepository.findAllByStatusAndSupplierCompanyId(status, myCompanyId, pageable);

        if (all.isEmpty()) {
            return new PageRes<>(Page.empty(pageable));
        }

        List<UUID> userIds = all.stream().map(Orders::getOrderedByUserId).distinct().toList();
        List<UUID> companyIds = new ArrayList<>();
        for(Orders order : all) {
            companyIds.add(order.getReceiverCompanyId());
            companyIds.add(order.getSupplierCompanyId());
        }
        List<UUID> distinctCompanyIds = companyIds.stream().distinct().toList();

        List<UsernameResDto> userNames = userCircuitService.getUserNames(userIds);
        List<CompanyNameResDto> companyNames = companyCircuitService.getCompanyNames(distinctCompanyIds);

        Map<UUID, String> userMap = userNames.stream().collect(Collectors.toMap(UsernameResDto::id, UsernameResDto::name));
        Map<UUID, String> companyMap = companyNames.stream().collect(Collectors.toMap(CompanyNameResDto::id, CompanyNameResDto::name));

        Page<UserOrderResDto> map = all.map(order -> UserOrderResDto.createOrdersRes(order, companyMap, userMap));
        return new PageRes<>(map);
    }

    private static Pageable getPageable(Pageable pageable) {
        int pageSize = pageable.getPageSize();
        if(pageSize != 10 && pageSize != 30 && pageSize != 50 ) {
            pageable = PageRequest.of(pageable.getPageNumber(), 10, pageable.getSort());
        }
        return pageable;
    }

    @Transactional
    public void publishDecreaseStockEvent(UUID orderId, ModifyStockReqDto items) {
        try{
            String jsonPayload = objectMapper.writeValueAsString(items);

            Outbox stockOutbox = Outbox.builder()
                    .aggregateType("STOCK")
                    .aggregateId(orderId.toString())
                    .topic("stock.decrease")
                    .payload(jsonPayload)
                    .processed(false)
                    .createdAt(LocalDateTime.now())
                    .build();

            Outbox savedOutbox = outboxRepository.save(stockOutbox);
            log.info("event=ORDER_OUTBOX_ENQUEUED topic={} orderId={} outboxId={}",
                    savedOutbox.getTopic(),
                    orderId,
                    savedOutbox.getId()
            );
        }catch (Exception e) {
            log.error("event=ORDER_STOCK_OUTBOX_SERIALIZE_FAILED orderId={}", orderId, e);
            throw new CustomException(OrderErrorCode.FAIL_OUTBOX);
        }
    }

    @Transactional
    public void publishMakeDeliveryEvent(MakeDeliveryReqDto makeDeliveryReqDto) {
        try{
            String jsonPayload = objectMapper.writeValueAsString(makeDeliveryReqDto);

            Outbox stockOutbox = Outbox.builder()
                    .aggregateType("DELIVERY")
                    .aggregateId(makeDeliveryReqDto.getOrderId().toString())
                    .topic("delivery.create")
                    .payload(jsonPayload)
                    .processed(false)
                    .createdAt(LocalDateTime.now())
                    .build();

            Outbox savedOutbox = outboxRepository.save(stockOutbox);
            log.info("event=ORDER_OUTBOX_ENQUEUED topic={} orderId={} outboxId={}",
                    savedOutbox.getTopic(),
                    makeDeliveryReqDto.getOrderId(),
                    savedOutbox.getId()
            );
        }catch (Exception e) {
            log.error("event=ORDER_DELIVERY_OUTBOX_SERIALIZE_FAILED orderId={}", makeDeliveryReqDto.getOrderId(), e);
            throw new CustomException(OrderErrorCode.FAIL_OUTBOX);
        }
    }

//    @Transactional
//    public MakeOrderDetailResDto makeOrders(OrderMakeReqDto orderMakeReqDto, UUID userId, UUID orderKey) {
//
//        String redisKey = "order:make" + orderKey;
//        Boolean b = redisTemplate.opsForValue().setIfAbsent(redisKey, "PROCESSING", Duration.ofMinutes(1));
//
//        if (Boolean.FALSE.equals(b)) {
//            throw new CustomException(OrderErrorCode.ALREADY_EXIST_ORDER);
//        }
//
//        // 1. 단순 조회성 외부 호출 (실패해도 원상복구 필요 없음)
//        List<UsernameResDto> ordererInfoList = userCircuitService.getUserNames(List.of(userId));
//        UsernameResDto orderer = ordererInfoList.isEmpty() ?
//                new UsernameResDto(userId, "알 수 없는 유저", null, "unknown@email.com") : ordererInfoList.get(0);
//
//        CompanyAddressResDto addressRes = companyCircuitService.companyAddress(orderMakeReqDto.getReceiverCompanyId());
//        String deliveryAddress = (addressRes != null) ? addressRes.getAddress() : "조회실패";
//
//        List<UUID> companyIds = List.of(orderMakeReqDto.getSupplierCompanyId(), orderMakeReqDto.getReceiverCompanyId());
//        List<OrderMakeReqDto.Items> items = orderMakeReqDto.getItems();
//
//        // 2. [상태 변경 시작] 외부 재고 차감 실행
//        List<ProductNPAResDto> nameAndPriceAndHubId = productCircuitService.decreaseProductStock(items);
//
//        // 재고 차감이 성공했음을 알리는 플래그 세팅
//        boolean isStockDecreased = true;
//
//        try { //재고는 깎였는데 이후 내 로직이나 DB 저장 중 터지는 경우 방어
//
//            // 회사 이름 및 상품 맵 조립
//            List<CompanyNameResDto> companyNameResDtos = companyCircuitService.getCompanyNames(companyIds);
//            Map<UUID, String> companyNameMap = companyNameResDtos.stream()
//                    .collect(Collectors.toMap(CompanyNameResDto::id, CompanyNameResDto::name));
//
//            String supplierCompanyName = companyNameMap.getOrDefault(orderMakeReqDto.getSupplierCompanyId(), "알 수 없는 공급사");
//            String receiverCompanyName = companyNameMap.getOrDefault(orderMakeReqDto.getReceiverCompanyId(), "알 수 없는 수령사");
//
//            Map<UUID, ProductNPAResDto> productMap = nameAndPriceAndHubId.stream()
//                    .collect(Collectors.toMap(ProductNPAResDto::productId, Function.identity()));
//
//            Orders initOrder = Orders.createInitOrder(orderMakeReqDto, userId);
//
//            for(OrderMakeReqDto.Items item : items) {
//                ProductNPAResDto productNPAResDto = productMap.get(item.getProductId());
//                OrderItems orderItem;
//
//                if (productNPAResDto != null) {
//                    orderItem = OrderItems.createOrderItem(
//                            item.getQuantity(),
//                            orderMakeReqDto.getSupplierCompanyId(),
//                            supplierCompanyName,
//                            productNPAResDto
//                    );
//                } else {
//                    orderItem = OrderItems.createFailedOrderItem(
//                            item.getProductId(),
//                            item.getQuantity(),
//                            orderMakeReqDto.getSupplierCompanyId(),
//                            supplierCompanyName
//                    );
//                }
//                initOrder.addOrderItem(orderItem);
//            }
//
//            initOrder.updateTotalPrice();
//
//            // 내 주문 DB에 선반영 (Id 발급 목적)
//            Orders savedOrder = orderJpaRepository.saveAndFlush(initOrder);
//
//            // 배송 생성 요청 DTO 조립
//            MakeDeliveryReqDto deliveryReqDto = MakeDeliveryReqDto.from(savedOrder, orderer, deliveryAddress, receiverCompanyName);
//
//            // 3. 정상 상품이 존재할 때만 배송 서비스 호출
//            if (!deliveryReqDto.getProducts().isEmpty()) {
//                try { //내 DB 저장도 끝났는데 오직 배송 API만 터진 경우 방어
//                    deliveryCircuitService.makeDelivery(deliveryReqDto);
//                } catch (Exception e) {
//                    log.error("[배송 실패 보상 로직] 배송 서비스 호출 실패로 인해 재고를 복구합니다. 원인: {}", e.getMessage());
//                    rollbackStock(items);
//                    throw new CustomException(OrderErrorCode.FAIL_DELIVERY); // 내 주문 DB도 롤백되도록 예외 토스
//                }
//            }
//
//            // 4. 최종 결과 DTO 반환부 (대박 성공 시나리오)
//            List<MakeOrderDetailResDto.OrderItemDto> itemDtos = savedOrder.getOrderItems().stream()
//                    .map(item -> new MakeOrderDetailResDto.OrderItemDto(
//                            item.getId(), item.getProductId(), item.getProductName(),
//                            item.getSupplierCompanyId(), item.getSupplierCompanyName(),
//                            item.getHubId(), item.getQuantity(), item.getUnitPrice(),
//                            item.getTotalPrice(), item.getStatus()
//                    )).toList();
//
//            return new MakeOrderDetailResDto(
//                    savedOrder.getId(), savedOrder.getSupplierCompanyId(), savedOrder.getReceiverCompanyId(),
//                    savedOrder.getOrderedByUserId(), savedOrder.getStatus(), savedOrder.getTotalPrice(),
//                    savedOrder.getRequestMemo(), savedOrder.getRequestedDeliveryDeadline(), itemDtos, savedOrder.getCreatedAt()
//            );
//
//        } catch (CustomException ce) {
//            // 배송 쪽에서 의도적으로 던진 CustomException은 그대로 통과시켜 상위 트랜잭션 롤백 유도
//            throw ce;
//        } catch (Exception e) {
//            // [주문 내부 로직 실패 방어] 맵 조립 에러나 saveAndFlush 등 주문 도중 터지면 재고 원상복구
//            log.error("[주문 내부 실패 보상 로직] 주문 처리 중 시스템 예외가 발생하여 재고를 복구합니다. 원인: {}", e.getMessage());
//            if (isStockDecreased) {
//                rollbackStock(items);
//            }
//            throw e; // 주문 DB 롤백 유도
//        }
//    }

    @Transactional
    public MakeOrderDetailResDto makeOrders(OrderMakeReqDto orderMakeReqDto, UUID userId, UUID orderKey) {
        log.info("event=ORDER_CREATE_REQUESTED userId={} orderKey={} supplierCompanyId={} receiverCompanyId={} itemCount={}",
                userId,
                orderKey,
                orderMakeReqDto.getSupplierCompanyId(),
                orderMakeReqDto.getReceiverCompanyId(),
                orderMakeReqDto.getItems() == null ? 0 : orderMakeReqDto.getItems().size()
        );

        // 레디스 중복 주문 방지
        String redisKey = "order:make" + orderKey;
        Boolean b = redisTemplate.opsForValue().setIfAbsent(redisKey, "PROCESSING", Duration.ofMinutes(1));

        if (Boolean.FALSE.equals(b)) {
            log.warn("event=ORDER_CREATE_DUPLICATED userId={} orderKey={}", userId, orderKey);
            throw new CustomException(OrderErrorCode.ALREADY_EXIST_ORDER);
        }

        // 주문 유저 정보 조회
        List<UsernameResDto> ordererInfoList = userCircuitService.getUserNames(List.of(userId));
        UsernameResDto orderer = ordererInfoList.isEmpty() ?
                new UsernameResDto(userId, "알 수 없는 유저", null, "unknown@email.com") : ordererInfoList.get(0);
        // 받는 회사 주소 조회
        CompanyAddressResDto addressRes = companyCircuitService.companyAddress(orderMakeReqDto.getReceiverCompanyId());
        String deliveryAddress = (addressRes != null) ? addressRes.getAddress() : "조회실패";

        List<UUID> companyIds = List.of(orderMakeReqDto.getSupplierCompanyId(), orderMakeReqDto.getReceiverCompanyId());
        List<OrderMakeReqDto.Items> items = orderMakeReqDto.getItems();

        // 재고 감소는 kafka 비동기로 처리
        // List<ProductNPAResDto> nameAndPriceAndHubId = productCircuitService.decreaseProductStock(items);

        // 3. 주문 초기 제공, 받음 회사명 조회
        List<CompanyNameResDto> companyNameResDtos = companyCircuitService.getCompanyNames(companyIds);
        Map<UUID, String> companyNameMap = companyNameResDtos.stream()
                .collect(Collectors.toMap(CompanyNameResDto::id, CompanyNameResDto::name));

        String supplierCompanyName = companyNameMap.getOrDefault(orderMakeReqDto.getSupplierCompanyId(), "알 수 없는 공급사");
        String receiverCompanyName = companyNameMap.getOrDefault(orderMakeReqDto.getReceiverCompanyId(), "알 수 없는 수령사");

        // 초기 Orders 생성 (PENDING)
        Orders initOrder = Orders.createInitOrder(orderMakeReqDto.getSupplierCompanyId(), orderMakeReqDto.getReceiverCompanyId(), orderMakeReqDto.getRequestMemo(), orderMakeReqDto.getRequestedDeliveryDeadline(), userId);

        for(OrderMakeReqDto.Items item : items) {
            //초기 OrderItems 생성 (PENDING) -> 나머지 필드는 재고 차감 성공후 채워주기
            OrderItems orderItem = OrderItems.createPendingOrderItem(
                    item.getProductId(),
                    item.getQuantity(),
                    orderMakeReqDto.getSupplierCompanyId(),
                    supplierCompanyName
            );
            initOrder.addOrderItem(orderItem);
        }

        // 아직 단가를 모르니 총액은 0원 세팅 -> 재고 차감 성공후 채워주기
        initOrder.setZeroTotalPrice();

        // 내 주문 DB에 선반영
        Orders savedOrder = orderJpaRepository.saveAndFlush(initOrder);
        log.info("event=ORDER_CREATED orderId={} userId={} status={} itemCount={}",
                savedOrder.getId(),
                userId,
                savedOrder.getStatus(),
                savedOrder.getOrderItems().size()
        );

        ModifyStockReqDto modifyStockReqDto = new ModifyStockReqDto(savedOrder.getId(), orderer.name(), orderer.email(), deliveryAddress, receiverCompanyName, items);
        // 재고 차감 Outbox 발행 메서드 호출
        publishDecreaseStockEvent(savedOrder.getId(), modifyStockReqDto);

        // 5. 최종 결과 DTO 반환부
        List<MakeOrderDetailResDto.OrderItemDto> itemDtos = savedOrder.getOrderItems().stream()
                .map(item -> new MakeOrderDetailResDto.OrderItemDto(
                        item.getId(), item.getProductId(), item.getProductName(),
                        item.getSupplierCompanyId(), item.getSupplierCompanyName(),
                        item.getHubId(), item.getQuantity(), item.getUnitPrice() == null ? 0 : item.getUnitPrice(),
                        item.getTotalPrice() == null ? 0 : item.getTotalPrice(), item.getStatus()
                )).toList();

        return new MakeOrderDetailResDto(
                savedOrder.getId(), savedOrder.getSupplierCompanyId(), savedOrder.getReceiverCompanyId(),
                savedOrder.getOrderedByUserId(), savedOrder.getStatus(), savedOrder.getTotalPrice(),
                savedOrder.getRequestMemo(), savedOrder.getRequestedDeliveryDeadline(), itemDtos, savedOrder.getCreatedAt()
        );

    }


    //재고 복구 전용 헬퍼 메서드
    private void rollbackStock(List<OrderMakeReqDto.Items> items) {
        try {
            productCircuitService.increaseProductStock(items);
            log.info("event=ORDER_STOCK_ROLLBACK_COMPLETED");
        } catch (Exception re) {
            log.error("event=ORDER_STOCK_ROLLBACK_FAILED reason={}", re.getMessage());
        }
    }

    public PageRes<UserOrderResDto> getReceivedOrders(UUID loginUserId, Status status, Pageable pageable) {

        pageable = getPageable(pageable);

        List<UsernameResDto> loginUserList = userCircuitService.getUserNames(List.of(loginUserId));

        if (loginUserList.isEmpty()) {
            return new PageRes<>(Page.empty(pageable));
        }

        // 내 소속 회사 ID 추출
        UUID myCompanyId = loginUserList.get(0).companyId();

        Page<Orders> all = orderJpaRepository.findAllByStatusAndReceiverCompanyId(status, myCompanyId, pageable);

        if (all.isEmpty()) {
            return new PageRes<>(Page.empty(pageable));
        }

        List<UUID> userIds = all.stream().map(Orders::getOrderedByUserId).distinct().toList();
        List<UUID> companyIds = new ArrayList<>();
        for(Orders order : all) {
            companyIds.add(order.getReceiverCompanyId());
            companyIds.add(order.getSupplierCompanyId());
        }
        List<UUID> distinctCompanyIds = companyIds.stream().distinct().toList();

        List<UsernameResDto> userNames = userCircuitService.getUserNames(userIds);
        List<CompanyNameResDto> companyNames = companyCircuitService.getCompanyNames(distinctCompanyIds);

        Map<UUID, String> userMap = userNames.stream().collect(Collectors.toMap(UsernameResDto::id, UsernameResDto::name));
        Map<UUID, String> companyMap = companyNames.stream().collect(Collectors.toMap(CompanyNameResDto::id, CompanyNameResDto::name));

        Page<UserOrderResDto> map = all.map(order -> UserOrderResDto.createOrdersRes(order, companyMap, userMap));
        return new PageRes<>(map);
    }

    public OrderDetailResDto getOrderById(UUID orderId) {
        Orders orders = orderJpaRepository.findByOrderId(orderId).orElseThrow(() -> new CustomException(OrderErrorCode.NOT_EXIST_ORDER));
        return OrderDetailResDto.from(orders);
    }

    @Transactional
    public void cancelOrder(UUID orderId) {
        Orders order = orderJpaRepository.findByOrderId(orderId)
                .orElseThrow(() -> new CustomException(OrderErrorCode.NOT_EXIST_ORDER));

        List<OrderMakeReqDto.Items> rollbackItems = order.getOrderItems().stream()
                .filter(item -> item.getStatus() == Status.COMPLETED)
                .map(item -> new OrderMakeReqDto.Items(item.getProductId(), item.getQuantity()))
                .toList();

        if (!rollbackItems.isEmpty()) {
            Boolean b = productCircuitService.increaseProductStock(rollbackItems);
            if(!b) throw new CustomException(OrderErrorCode.FAIL_INCREASE_STOCK);
        }

        order.cancel();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markOutboxProcessed(UUID outboxId) {
        outboxRepository.findById(outboxId).ifPresent(Outbox::markProcessed);
    }

    @Transactional
    public void processStockSuccess(StockResultDto result) {
        UUID orderId = result.getOrderId();
        log.info("event=STOCK_DECREASE_SUCCEEDED orderId={} productCount={}",
                orderId,
                result.getProducts() == null ? 0 : result.getProducts().size()
        );
        Orders order = orderJpaRepository.findById(orderId)
                .orElseThrow(() -> new CustomException(OrderErrorCode.NOT_EXIST_ORDER));

        if (order.getStatus() != Status.PENDING) {
            log.warn("event=STOCK_DECREASE_ALREADY_PROCESSED orderId={}", orderId);
            return;
        }

        Map<UUID, ProductNPAResDto> productMap = result.getProducts().stream()
                .collect(Collectors.toMap(ProductNPAResDto::productId, Function.identity()));

        order.getOrderItems().forEach(item -> {
            ProductNPAResDto info = productMap.get(item.getProductId());
            if (info != null) {
                item.enrichProductDetails(info.name(), info.price(), info.hubId());
                item.setStatus(Status.COMPLETED);
            }
        });

        order.updateTotalPrice();
        order.setStatus(Status.CREATED);

        MakeDeliveryReqDto deliveryReqDto = MakeDeliveryReqDto.from(
                order,
                result.getOrdererName(),
                result.getOrdererEmail(),
                result.getDeliveryAddress(),
                result.getReceiverCompanyName()
        );

        this.publishMakeDeliveryEvent(deliveryReqDto);
        log.info("event=DELIVERY_CREATE_REQUEST_PREPARED orderId={} status={} totalPrice={}",
                orderId,
                order.getStatus(),
                order.getTotalPrice()
        );
    }

    @Transactional
    public void processStockFailed(UUID orderId) {
        log.warn("event=STOCK_DECREASE_FAILED orderId={}", orderId);
        Orders order = orderJpaRepository.findById(orderId).orElse(null);
        if (order != null) {
            order.setStatus(Status.FAILED);
            order.getOrderItems().forEach(item -> item.setStatus(Status.FAILED));
            log.warn("event=ORDER_FAILED_BY_STOCK orderId={}", orderId);
        }
    }

    @Transactional
    public void processDeliverySuccess(UUID orderId) {
        Orders order = orderJpaRepository.findById(orderId).orElse(null);
        if (order != null) {
            order.setStatus(Status.COMPLETED);
            log.info("event=ORDER_COMPLETED orderId={}", orderId);
        }
    }

    @Transactional
    public void processDeliveryFailed(UUID orderId) throws com.fasterxml.jackson.core.JsonProcessingException {
        log.warn("event=DELIVERY_CREATE_FAILED_CONSUMED orderId={}", orderId);
        Orders order = orderJpaRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 주문입니다. ID: " + orderId));

        if (order.getStatus() == Status.FAILED) {
            log.warn("event=ORDER_ALREADY_FAILED orderId={}", orderId);
            return;
        }

        order.setStatus(Status.FAILED);
        order.getOrderItems().forEach(item -> item.setStatus(Status.FAILED));

        List<com.msa.order_service.dto.req.OrderMakeReqDto.Items> rollbackItems = order.getOrderItems().stream()
                .map(item -> new com.msa.order_service.dto.req.OrderMakeReqDto.Items(item.getProductId(), item.getQuantity()))
                .toList();

        String rollbackPayload = objectMapper.writeValueAsString(rollbackItems);
        Outbox stockRollbackOutbox = Outbox.builder()
                .aggregateType("ORDER")
                .aggregateId(orderId.toString())
                .topic("stock.increase")
                .payload(rollbackPayload)
                .processed(false)
                .createdAt(java.time.LocalDateTime.now())
                .build();

        Outbox savedOutbox = outboxRepository.save(stockRollbackOutbox);
        log.warn("event=STOCK_ROLLBACK_OUTBOX_ENQUEUED orderId={} outboxId={}",
                orderId,
                savedOutbox.getId()
        );
    }

}
