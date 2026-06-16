BEGIN;

-- 배송 부하테스트 런타임 데이터 초기화
TRUNCATE TABLE
    delivery_service.p_delivery_route_histories,
    delivery_service.p_deliveries,
    delivery_service.p_delivery_outboxes
RESTART IDENTITY CASCADE;

-- 배송 로직 부하테스트 기준 허브 데이터 복원
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

-- 배송 로직 부하테스트 기준 허브 경로 복원
INSERT INTO hub_service.p_hub_routes (
    hub_route_id, departure_hub_id, arrival_hub_id,
    estimated_distance_km, estimated_duration_min, route_type,
    created_at, created_by, updated_at, updated_by
) VALUES
    ('11000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002', 325.00, 260, 'H2H', now(), 'seed', now(), 'seed'),
    ('11000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 325.00, 260, 'H2H', now(), 'seed', now(), 'seed'),
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

-- 배송 로직 부하테스트 기준 업체 복원
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

-- 배송 로직 부하테스트 기준 허브 매니저 복원
INSERT INTO user_service.p_users (
    user_id, username, password, name, email, slack_id, role, status, hub_id, company_id,
    created_at, created_by, updated_at, updated_by, deleted_at, deleted_by
) VALUES
    ('50000000-0000-0000-0000-000000000002', 'seoul-hub-manager', '$2a$10$4scVAESgtgp72lfHEWt/0.8Fr2lK5gencJxQlFnbANzhCmjfEgaMm', '서울 허브 매니저', 'seoul-hub-manager@hublink.test', 'seoul-hub-manager', 'HUB_MANAGER', 'APPROVED', '10000000-0000-0000-0000-000000000001', null, now(), 'seed', now(), 'seed', null, null)
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
    updated_by = 'seed',
    deleted_at = null,
    deleted_by = null;

-- 배송 로직 부하테스트 기준 배송 담당자 풀 정리
-- 50VU 집중/분산 조건에서 max-active-per-manager=30 제한에 바로 막히지 않도록
-- 서울 HUB_DELIVERY, 부산 COMPANY_DELIVERY, 인천 COMPANY_DELIVERY를 각각 120명씩 복원
DELETE FROM user_service.p_delivery_managers
WHERE user_id IN (
    SELECT ('50000000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid
    FROM (
        SELECT generate_series(1000, 1119) AS seq
        UNION ALL
        SELECT generate_series(4000, 4119) AS seq
        UNION ALL
        SELECT generate_series(7000, 7119) AS seq
    ) managed_seed
);

DELETE FROM user_service.p_users
WHERE user_id IN (
    SELECT ('50000000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid
    FROM (
        SELECT generate_series(1000, 1119) AS seq
        UNION ALL
        SELECT generate_series(4000, 4119) AS seq
        UNION ALL
        SELECT generate_series(7000, 7119) AS seq
    ) managed_seed
);

-- 배송 로직 부하테스트 기준 배송 담당자 사용자 복원
INSERT INTO user_service.p_users (
    user_id, username, password, name, email, slack_id, role, status, hub_id, company_id,
    created_at, created_by, updated_at, updated_by
)
SELECT
    ('50000000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid,
    prefix || '-' || lpad((delivery_seq)::text, 3, '0'),
    '$2a$10$4scVAESgtgp72lfHEWt/0.8Fr2lK5gencJxQlFnbANzhCmjfEgaMm',
    display_name || ' ' || lpad((delivery_seq)::text, 3, '0'),
    prefix || '-' || lpad((delivery_seq)::text, 3, '0') || '@hublink.test',
    prefix || '-' || lpad((delivery_seq)::text, 3, '0'),
    'DELIVERY_MANAGER',
    'APPROVED',
    hub_id,
    null,
    now(),
    'seed',
    now(),
    'seed'
FROM (
    SELECT seq_num AS seq, 'load-seoul-hub-delivery' AS prefix, '서울 허브 배송담당자' AS display_name, '10000000-0000-0000-0000-000000000001'::uuid AS hub_id, seq_num - 999 AS delivery_seq
    FROM generate_series(1000, 1119) AS seq_num
    UNION ALL
    SELECT seq_num AS seq, 'load-busan-company-delivery' AS prefix, '부산 업체 배송담당자' AS display_name, '10000000-0000-0000-0000-000000000002'::uuid AS hub_id, seq_num - 3999 AS delivery_seq
    FROM generate_series(4000, 4119) AS seq_num
    UNION ALL
    SELECT seq_num AS seq, 'load-incheon-company-delivery' AS prefix, '인천 업체 배송담당자' AS display_name, '10000000-0000-0000-0000-000000000003'::uuid AS hub_id, seq_num - 6999 AS delivery_seq
    FROM generate_series(7000, 7119) AS seq_num
) manager_user_seed;

-- 배송 로직 부하테스트 기준 배송 담당자 복원
INSERT INTO user_service.p_delivery_managers (
    user_id, hub_id, type, delivery_sequence, slack_id,
    created_at, created_by, updated_at, updated_by
)
SELECT
    ('50000000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid,
    hub_id,
    manager_type,
    delivery_seq,
    prefix || '-' || lpad((delivery_seq)::text, 3, '0'),
    now(),
    'seed',
    now(),
    'seed'
FROM (
    SELECT seq_num AS seq, 'load-seoul-hub-delivery' AS prefix, '10000000-0000-0000-0000-000000000001'::uuid AS hub_id, 'HUB_DELIVERY' AS manager_type, seq_num - 999 AS delivery_seq
    FROM generate_series(1000, 1119) AS seq_num
    UNION ALL
    SELECT seq_num AS seq, 'load-busan-company-delivery' AS prefix, '10000000-0000-0000-0000-000000000002'::uuid AS hub_id, 'COMPANY_DELIVERY' AS manager_type, seq_num - 3999 AS delivery_seq
    FROM generate_series(4000, 4119) AS seq_num
    UNION ALL
    SELECT seq_num AS seq, 'load-incheon-company-delivery' AS prefix, '10000000-0000-0000-0000-000000000003'::uuid AS hub_id, 'COMPANY_DELIVERY' AS manager_type, seq_num - 6999 AS delivery_seq
    FROM generate_series(7000, 7119) AS seq_num
) manager_seed;

COMMIT;
