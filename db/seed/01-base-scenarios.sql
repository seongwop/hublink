BEGIN;

-- 허브 기준 데이터
INSERT INTO hub_service.p_hubs (
    hub_id, name, address, latitude, longitude,
    created_at, created_by, updated_at, updated_by
) VALUES
    ('10000000-0000-0000-0000-000000000001', '서울 허브', '서울특별시 중구 세종대로 110', 37.5665000, 126.9780000, now(), 'seed', now(), 'seed'),
    ('10000000-0000-0000-0000-000000000002', '부산 허브', '부산광역시 연제구 중앙대로 1001', 35.1796000, 129.0756000, now(), 'seed', now(), 'seed'),
    ('10000000-0000-0000-0000-000000000003', '인천 허브', '인천광역시 남동구 정각로 29', 37.4563000, 126.7052000, now(), 'seed', now(), 'seed')
ON CONFLICT (hub_id) DO UPDATE SET
    name = EXCLUDED.name,
    address = EXCLUDED.address,
    latitude = EXCLUDED.latitude,
    longitude = EXCLUDED.longitude,
    updated_at = now(),
    updated_by = 'seed';

-- 정상 배송용 허브 경로
INSERT INTO hub_service.p_hub_routes (
    hub_route_id, departure_hub_id, arrival_hub_id,
    estimated_distance_km, estimated_duration_min, route_type,
    created_at, created_by, updated_at, updated_by
) VALUES
    ('11000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002', 325.00, 260, 'H2H', now(), 'seed', now(), 'seed'),
    ('11000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 325.00, 260, 'H2H', now(), 'seed', now(), 'seed')
ON CONFLICT (hub_route_id) DO UPDATE SET
    departure_hub_id = EXCLUDED.departure_hub_id,
    arrival_hub_id = EXCLUDED.arrival_hub_id,
    estimated_distance_km = EXCLUDED.estimated_distance_km,
    estimated_duration_min = EXCLUDED.estimated_duration_min,
    route_type = EXCLUDED.route_type,
    updated_at = now(),
    updated_by = 'seed';

-- 업체 기준 데이터
INSERT INTO company_service.p_companies (
    company_id, hub_id, name, type, address, latitude, longitude,
    created_at, created_by, updated_at, updated_by
) VALUES
    ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '서울 공급업체', 0, '서울특별시 중구 남대문로 81', 37.5636000, 126.9820000, now(), 'seed', now(), 'seed'),
    ('20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002', '부산 수령업체', 1, '부산광역시 해운대구 센텀중앙로 97', 35.1695000, 129.1307000, now(), 'seed', now(), 'seed'),
    ('20000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000003', '인천 수령업체', 1, '인천광역시 연수구 컨벤시아대로 165', 37.3895000, 126.6450000, now(), 'seed', now(), 'seed')
ON CONFLICT (company_id) DO UPDATE SET
    hub_id = EXCLUDED.hub_id,
    name = EXCLUDED.name,
    type = EXCLUDED.type,
    address = EXCLUDED.address,
    latitude = EXCLUDED.latitude,
    longitude = EXCLUDED.longitude,
    updated_at = now(),
    updated_by = 'seed';

-- 상품 기준 데이터
INSERT INTO product_service.p_product (
    product_id, company_id, hub_id, name, price, version,
    created_at, created_by, updated_at, updated_by
) VALUES
    ('30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '성공 시나리오 상품', 12000, 0, now(), 'seed', now(), 'seed'),
    ('30000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '재고 부족 상품', 8000, 0, now(), 'seed', now(), 'seed'),
    ('30000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '배송 실패 보상 상품', 15000, 0, now(), 'seed', now(), 'seed')
ON CONFLICT (product_id) DO UPDATE SET
    company_id = EXCLUDED.company_id,
    hub_id = EXCLUDED.hub_id,
    name = EXCLUDED.name,
    price = EXCLUDED.price,
    updated_at = now(),
    updated_by = 'seed';

-- 재고 기준 데이터
INSERT INTO stock_service.p_stock (
    id, product_id, hub_id, quantity, reserved_quantity, version,
    created_at, created_by, updated_at, updated_by
) VALUES
    ('40000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 1000, 0, 0, now(), 'seed', now(), 'seed'),
    ('40000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 1, 0, 0, now(), 'seed', now(), 'seed'),
    ('40000000-0000-0000-0000-000000000003', '30000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001', 1000, 0, 0, now(), 'seed', now(), 'seed')
ON CONFLICT (id) DO UPDATE SET
    product_id = EXCLUDED.product_id,
    hub_id = EXCLUDED.hub_id,
    quantity = EXCLUDED.quantity,
    reserved_quantity = EXCLUDED.reserved_quantity,
    updated_at = now(),
    updated_by = 'seed';

-- 사용자 기준 데이터
INSERT INTO user_service.p_users (
    user_id, username, password, name, email, slack_id, role, status, hub_id, company_id,
    created_at, created_by, updated_at, updated_by
) VALUES
    ('50000000-0000-0000-0000-000000000000', 'master', '$2a$10$4scVAESgtgp72lfHEWt/0.8Fr2lK5gencJxQlFnbANzhCmjfEgaMm', '마스터 관리자', 'master@hublink.test', 'master', 'MASTER', 'APPROVED', null, null, now(), 'seed', now(), 'seed'),
    ('50000000-0000-0000-0000-000000000001', 'buyer-manager', '$2a$10$4scVAESgtgp72lfHEWt/0.8Fr2lK5gencJxQlFnbANzhCmjfEgaMm', '구매 담당자', 'buyer-manager@hublink.test', 'buyer-manager', 'COMPANY_MANAGER', 'APPROVED', null, '20000000-0000-0000-0000-000000000002', now(), 'seed', now(), 'seed'),
    ('50000000-0000-0000-0000-000000000002', 'seoul-hub-manager', '$2a$10$4scVAESgtgp72lfHEWt/0.8Fr2lK5gencJxQlFnbANzhCmjfEgaMm', '서울 허브 매니저', 'seoul-hub-manager@hublink.test', 'seoul-hub-manager', 'HUB_MANAGER', 'APPROVED', '10000000-0000-0000-0000-000000000001', null, now(), 'seed', now(), 'seed'),
    ('50000000-0000-0000-0000-000000000003', 'seoul-hub-delivery', '$2a$10$4scVAESgtgp72lfHEWt/0.8Fr2lK5gencJxQlFnbANzhCmjfEgaMm', '서울 허브 배송담당자', 'seoul-hub-delivery@hublink.test', 'seoul-hub-delivery', 'DELIVERY_MANAGER', 'APPROVED', '10000000-0000-0000-0000-000000000001', null, now(), 'seed', now(), 'seed'),
    ('50000000-0000-0000-0000-000000000004', 'busan-company-delivery', '$2a$10$4scVAESgtgp72lfHEWt/0.8Fr2lK5gencJxQlFnbANzhCmjfEgaMm', '부산 업체 배송담당자', 'busan-company-delivery@hublink.test', 'busan-company-delivery', 'DELIVERY_MANAGER', 'APPROVED', '10000000-0000-0000-0000-000000000002', null, now(), 'seed', now(), 'seed'),
    ('50000000-0000-0000-0000-000000000005', 'incheon-company-delivery', '$2a$10$4scVAESgtgp72lfHEWt/0.8Fr2lK5gencJxQlFnbANzhCmjfEgaMm', '인천 업체 배송담당자', 'incheon-company-delivery@hublink.test', 'incheon-company-delivery', 'DELIVERY_MANAGER', 'APPROVED', '10000000-0000-0000-0000-000000000003', null, now(), 'seed', now(), 'seed')
ON CONFLICT (user_id) DO UPDATE SET
    username = EXCLUDED.username,
    password = EXCLUDED.password,
    name = EXCLUDED.name,
    email = EXCLUDED.email,
    slack_id = EXCLUDED.slack_id,
    role = EXCLUDED.role,
    status = EXCLUDED.status,
    hub_id = EXCLUDED.hub_id,
    company_id = EXCLUDED.company_id,
    updated_at = now(),
    updated_by = 'seed';

-- 배송 담당자 기준 데이터
INSERT INTO user_service.p_delivery_managers (
    user_id, hub_id, type, delivery_sequence, slack_id,
    created_at, created_by, updated_at, updated_by
) VALUES
    ('50000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001', 'HUB_DELIVERY', 1, 'seoul-hub-delivery', now(), 'seed', now(), 'seed'),
    ('50000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000002', 'COMPANY_DELIVERY', 1, 'busan-company-delivery', now(), 'seed', now(), 'seed'),
    ('50000000-0000-0000-0000-000000000005', '10000000-0000-0000-0000-000000000003', 'COMPANY_DELIVERY', 1, 'incheon-company-delivery', now(), 'seed', now(), 'seed')
ON CONFLICT (user_id) DO UPDATE SET
    hub_id = EXCLUDED.hub_id,
    type = EXCLUDED.type,
    delivery_sequence = EXCLUDED.delivery_sequence,
    slack_id = EXCLUDED.slack_id,
    updated_at = now(),
    updated_by = 'seed';

-- 부하테스트용 허브 경로 추가
INSERT INTO hub_service.p_hub_routes (
    hub_route_id, departure_hub_id, arrival_hub_id,
    estimated_distance_km, estimated_duration_min, route_type,
    created_at, created_by, updated_at, updated_by
) VALUES
    ('11000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000003', 36.00, 45, 'P2P', now(), 'seed', now(), 'seed'),
    ('11000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001', 36.00, 45, 'P2P', now(), 'seed', now(), 'seed'),
    ('11000000-0000-0000-0000-000000000005', '10000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000003', 330.00, 265, 'H2H', now(), 'seed', now(), 'seed'),
    ('11000000-0000-0000-0000-000000000006', '10000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000002', 330.00, 265, 'H2H', now(), 'seed', now(), 'seed')
ON CONFLICT (hub_route_id) DO UPDATE SET
    departure_hub_id = EXCLUDED.departure_hub_id,
    arrival_hub_id = EXCLUDED.arrival_hub_id,
    estimated_distance_km = EXCLUDED.estimated_distance_km,
    estimated_duration_min = EXCLUDED.estimated_duration_min,
    route_type = EXCLUDED.route_type,
    updated_at = now(),
    updated_by = 'seed';

-- 부하테스트용 수령업체 추가
INSERT INTO company_service.p_companies (
    company_id, hub_id, name, type, address, latitude, longitude,
    created_at, created_by, updated_at, updated_by
)
SELECT
    ('20000000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid,
    CASE WHEN seq < 20
        THEN '10000000-0000-0000-0000-000000000002'::uuid
        ELSE '10000000-0000-0000-0000-000000000003'::uuid
    END,
    CASE WHEN seq < 20
        THEN 'busan-receiver-' || lpad((seq - 9)::text, 2, '0')
        ELSE 'incheon-receiver-' || lpad((seq - 19)::text, 2, '0')
    END,
    1,
    CASE WHEN seq < 20
        THEN 'Busan receiver address ' || lpad((seq - 9)::text, 2, '0')
        ELSE 'Incheon receiver address ' || lpad((seq - 19)::text, 2, '0')
    END,
    CASE WHEN seq < 20
        THEN 35.1700000 + ((seq - 9)::numeric * 0.0001000)
        ELSE 37.3900000 + ((seq - 19)::numeric * 0.0001000)
    END,
    CASE WHEN seq < 20
        THEN 129.1310000 + ((seq - 9)::numeric * 0.0001000)
        ELSE 126.6460000 + ((seq - 19)::numeric * 0.0001000)
    END,
    now(),
    'seed',
    now(),
    'seed'
FROM (
    SELECT generate_series(10, 17) AS seq
    UNION ALL
    SELECT generate_series(20, 27) AS seq
) receiver_seed
ON CONFLICT (company_id) DO UPDATE SET
    hub_id = EXCLUDED.hub_id,
    name = EXCLUDED.name,
    type = EXCLUDED.type,
    address = EXCLUDED.address,
    latitude = EXCLUDED.latitude,
    longitude = EXCLUDED.longitude,
    updated_at = now(),
    updated_by = 'seed';

-- 부하테스트용 배송 담당자 사용자 추가
DELETE FROM user_service.p_delivery_managers
WHERE user_id IN (
    SELECT ('50000000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid
    FROM (
        SELECT generate_series(10, 18) AS seq
        UNION ALL
        SELECT generate_series(20, 28) AS seq
        UNION ALL
        SELECT generate_series(30, 38) AS seq
        UNION ALL
        SELECT generate_series(1000, 2999) AS seq
        UNION ALL
        SELECT generate_series(4000, 5999) AS seq
        UNION ALL
        SELECT generate_series(7000, 8999) AS seq
    ) cleanup_seed
);

DELETE FROM user_service.p_users
WHERE user_id IN (
    SELECT ('50000000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid
    FROM (
        SELECT generate_series(10, 18) AS seq
        UNION ALL
        SELECT generate_series(20, 28) AS seq
        UNION ALL
        SELECT generate_series(30, 38) AS seq
        UNION ALL
        SELECT generate_series(1000, 2999) AS seq
        UNION ALL
        SELECT generate_series(4000, 5999) AS seq
        UNION ALL
        SELECT generate_series(7000, 8999) AS seq
    ) cleanup_seed
);

INSERT INTO user_service.p_users (
    user_id, username, password, name, email, slack_id, role, status, hub_id, company_id,
    created_at, created_by, updated_at, updated_by
)
SELECT
    ('50000000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid,
    prefix || '-' || lpad(seq::text, 4, '0'),
    '$2a$10$4scVAESgtgp72lfHEWt/0.8Fr2lK5gencJxQlFnbANzhCmjfEgaMm',
    replace(prefix, '-', ' ') || ' ' || lpad(seq::text, 4, '0'),
    prefix || '-' || lpad(seq::text, 4, '0') || '@hublink.test',
    prefix || '-' || lpad(seq::text, 4, '0'),
    'DELIVERY_MANAGER',
    'APPROVED',
    hub_id,
    null,
    now(),
    'seed',
    now(),
    'seed'
FROM (
    SELECT hub_delivery.seq_num AS seq, 'load-seoul-hub-delivery' AS prefix, '10000000-0000-0000-0000-000000000001'::uuid AS hub_id, hub_delivery.seq_num - 998 AS delivery_seq
    FROM (SELECT generate_series(1000, 2999) AS seq_num) hub_delivery
    UNION ALL
    SELECT busan_company_delivery.seq_num AS seq, 'load-busan-company-delivery' AS prefix, '10000000-0000-0000-0000-000000000002'::uuid AS hub_id, busan_company_delivery.seq_num - 3998 AS delivery_seq
    FROM (SELECT generate_series(4000, 5999) AS seq_num) busan_company_delivery
    UNION ALL
    SELECT incheon_company_delivery.seq_num AS seq, 'load-incheon-company-delivery' AS prefix, '10000000-0000-0000-0000-000000000003'::uuid AS hub_id, incheon_company_delivery.seq_num - 6998 AS delivery_seq
    FROM (SELECT generate_series(7000, 8999) AS seq_num) incheon_company_delivery
) manager_user_seed
ON CONFLICT (user_id) DO UPDATE SET
    username = EXCLUDED.username,
    password = EXCLUDED.password,
    name = EXCLUDED.name,
    email = EXCLUDED.email,
    slack_id = EXCLUDED.slack_id,
    role = EXCLUDED.role,
    status = EXCLUDED.status,
    hub_id = EXCLUDED.hub_id,
    company_id = EXCLUDED.company_id,
    updated_at = now(),
    updated_by = 'seed';

-- 부하테스트용 배송 담당자 추가
INSERT INTO user_service.p_delivery_managers (
    user_id, hub_id, type, delivery_sequence, slack_id,
    created_at, created_by, updated_at, updated_by
)
SELECT
    ('50000000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid,
    hub_id,
    manager_type,
    delivery_seq,
    prefix || '-' || lpad(seq::text, 4, '0'),
    now(),
    'seed',
    now(),
    'seed'
FROM (
    SELECT hub_delivery.seq_num AS seq, 'load-seoul-hub-delivery' AS prefix, '10000000-0000-0000-0000-000000000001'::uuid AS hub_id, 'HUB_DELIVERY' AS manager_type, hub_delivery.seq_num - 998 AS delivery_seq
    FROM (SELECT generate_series(1000, 2999) AS seq_num) hub_delivery
    UNION ALL
    SELECT busan_company_delivery.seq_num AS seq, 'load-busan-company-delivery' AS prefix, '10000000-0000-0000-0000-000000000002'::uuid AS hub_id, 'COMPANY_DELIVERY' AS manager_type, busan_company_delivery.seq_num - 3998 AS delivery_seq
    FROM (SELECT generate_series(4000, 5999) AS seq_num) busan_company_delivery
    UNION ALL
    SELECT incheon_company_delivery.seq_num AS seq, 'load-incheon-company-delivery' AS prefix, '10000000-0000-0000-0000-000000000003'::uuid AS hub_id, 'COMPANY_DELIVERY' AS manager_type, incheon_company_delivery.seq_num - 6998 AS delivery_seq
    FROM (SELECT generate_series(7000, 8999) AS seq_num) incheon_company_delivery
) manager_seed
ON CONFLICT (user_id) DO UPDATE SET
    hub_id = EXCLUDED.hub_id,
    type = EXCLUDED.type,
    delivery_sequence = EXCLUDED.delivery_sequence,
    slack_id = EXCLUDED.slack_id,
    updated_at = now(),
    updated_by = 'seed';

COMMIT;
