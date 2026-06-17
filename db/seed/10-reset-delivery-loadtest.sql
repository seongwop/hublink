BEGIN;

-- 배송 부하테스트 런타임 테이블 초기화
TRUNCATE TABLE
    delivery_service.p_delivery_route_histories,
    delivery_service.p_deliveries,
    delivery_service.p_delivery_outboxes
RESTART IDENTITY CASCADE;

COMMIT;
