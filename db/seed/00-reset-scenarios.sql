BEGIN;

-- 시나리오 런타임 데이터 초기화
TRUNCATE TABLE
    delivery_service.p_delivery_route_histories,
    delivery_service.p_deliveries,
    delivery_service.p_delivery_outboxes,
    order_service.p_order_items,
    order_service.p_orders,
    order_service.p_outbox,
    stock_service.p_stock_histories
RESTART IDENTITY CASCADE;

-- 시나리오 재고 수량 복원
UPDATE stock_service.p_stock
SET quantity = CASE product_id
        WHEN '30000000-0000-0000-0000-000000000001' THEN 1000
        WHEN '30000000-0000-0000-0000-000000000002' THEN 1
        WHEN '30000000-0000-0000-0000-000000000003' THEN 1000
        ELSE quantity
    END,
    reserved_quantity = CASE product_id
        WHEN '30000000-0000-0000-0000-000000000001' THEN 0
        WHEN '30000000-0000-0000-0000-000000000002' THEN 0
        WHEN '30000000-0000-0000-0000-000000000003' THEN 0
        ELSE reserved_quantity
    END,
    updated_at = now(),
    updated_by = 'seed'
WHERE product_id IN (
    '30000000-0000-0000-0000-000000000001',
    '30000000-0000-0000-0000-000000000002',
    '30000000-0000-0000-0000-000000000003'
);

COMMIT;
