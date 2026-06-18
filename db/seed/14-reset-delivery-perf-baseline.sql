BEGIN;

-- 배송 성능 비교 baseline 초기화
TRUNCATE TABLE
    delivery_service.p_delivery_route_histories,
    delivery_service.p_deliveries,
    delivery_service.p_delivery_outboxes
RESTART IDENTITY CASCADE;

-- 허브 복원
INSERT INTO hub_service.p_hubs (
    hub_id, name, address, latitude, longitude,
    created_at, created_by, updated_at, updated_by
) VALUES
    ('10000000-0000-0000-0000-000000000001', 'Seoul Hub', '110 Sejong-daero, Jung-gu, Seoul', 37.5665000, 126.9780000, now(), 'seed', now(), 'seed'),
    ('10000000-0000-0000-0000-000000000002', 'Busan Hub', '1001 Jungang-daero, Yeonje-gu, Busan', 35.1796000, 129.0756000, now(), 'seed', now(), 'seed'),
    ('10000000-0000-0000-0000-000000000003', 'Incheon Hub', '29 Jeonggak-ro, Namdong-gu, Incheon', 37.4563000, 126.7052000, now(), 'seed', now(), 'seed')
ON CONFLICT (hub_id) DO UPDATE SET
    name = EXCLUDED.name,
    address = EXCLUDED.address,
    latitude = EXCLUDED.latitude,
    longitude = EXCLUDED.longitude,
    updated_at = now(),
    updated_by = 'seed';

-- 허브 경로 복원
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

-- 회사 복원
INSERT INTO company_service.p_companies (
    company_id, hub_id, name, type, address, latitude, longitude,
    created_at, created_by, updated_at, updated_by
) VALUES
    ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'seoul-supplier', 0, '81 Eulji-ro, Jung-gu, Seoul', 37.5636000, 126.9820000, now(), 'seed', now(), 'seed'),
    ('20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002', 'busan-receiver-main', 1, '97 Haeundaehaebyeon-ro, Haeundae-gu, Busan', 35.1695000, 129.1307000, now(), 'seed', now(), 'seed'),
    ('20000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000003', 'incheon-receiver-main', 1, '165 Aenggogae-ro, Yeonsu-gu, Incheon', 37.3895000, 126.6450000, now(), 'seed', now(), 'seed')
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
    CASE
        WHEN seq < 20 THEN '10000000-0000-0000-0000-000000000002'::uuid
        ELSE '10000000-0000-0000-0000-000000000003'::uuid
    END,
    CASE
        WHEN seq < 20 THEN 'busan-receiver-' || lpad((seq - 9)::text, 2, '0')
        ELSE 'incheon-receiver-' || lpad((seq - 19)::text, 2, '0')
    END,
    1,
    CASE
        WHEN seq < 20 THEN 'Busan receiver address ' || lpad((seq - 9)::text, 2, '0')
        ELSE 'Incheon receiver address ' || lpad((seq - 19)::text, 2, '0')
    END,
    CASE
        WHEN seq < 20 THEN 35.1700000 + ((seq - 9)::numeric * 0.0001000)
        ELSE 37.3900000 + ((seq - 19)::numeric * 0.0001000)
    END,
    CASE
        WHEN seq < 20 THEN 129.1310000 + ((seq - 9)::numeric * 0.0001000)
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

-- 허브 매니저 복원
INSERT INTO user_service.p_users (
    user_id, username, password, name, email, slack_id, role, status, hub_id, company_id,
    created_at, created_by, updated_at, updated_by, deleted_at, deleted_by
) VALUES
    ('50000000-0000-0000-0000-000000000002', 'seoul-hub-manager', '$2a$10$4scVAESgtgp72lfHEWt/0.8Fr2lK5gencJxQlFnbANzhCmjfEgaMm', 'Seoul Hub Manager', 'seoul-hub-manager@hublink.test', 'seoul-hub-manager', 'HUB_MANAGER', 'APPROVED', '10000000-0000-0000-0000-000000000001', null, now(), 'seed', now(), 'seed', null, null)
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

-- 기존 테스트 담당자 정리
DELETE FROM user_service.p_delivery_managers
WHERE user_id IN (
    SELECT ('50000000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid
    FROM (
        SELECT generate_series(1000, 2999) AS seq
        UNION ALL
        SELECT generate_series(4000, 5999) AS seq
        UNION ALL
        SELECT generate_series(7000, 8999) AS seq
    ) managed_seed
)
OR user_id IN (
    '50000000-0000-0000-0000-000000000003'::uuid,
    '50000000-0000-0000-0000-000000000004'::uuid,
    '50000000-0000-0000-0000-000000000005'::uuid
);

DELETE FROM user_service.p_users
WHERE user_id IN (
    SELECT ('50000000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid
    FROM (
        SELECT generate_series(1000, 2999) AS seq
        UNION ALL
        SELECT generate_series(4000, 5999) AS seq
        UNION ALL
        SELECT generate_series(7000, 8999) AS seq
    ) managed_seed
)
OR user_id IN (
    '50000000-0000-0000-0000-000000000003'::uuid,
    '50000000-0000-0000-0000-000000000004'::uuid,
    '50000000-0000-0000-0000-000000000005'::uuid
);

-- 배송 담당자 사용자 복원
INSERT INTO user_service.p_users (
    user_id, username, password, name, email, slack_id, role, status, hub_id, company_id,
    created_at, created_by, updated_at, updated_by, deleted_at, deleted_by
)
SELECT
    ('50000000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid,
    prefix || '-' || lpad(delivery_seq::text, 3, '0'),
    '$2a$10$4scVAESgtgp72lfHEWt/0.8Fr2lK5gencJxQlFnbANzhCmjfEgaMm',
    display_name || ' ' || lpad(delivery_seq::text, 3, '0'),
    prefix || '-' || lpad(delivery_seq::text, 3, '0') || '@hublink.test',
    prefix || '-' || lpad(delivery_seq::text, 3, '0'),
    'DELIVERY_MANAGER',
    'APPROVED',
    hub_id,
    null,
    now(),
    'seed',
    now(),
    'seed',
    null,
    null
FROM (
    SELECT
        seq_num AS seq,
        'perf-seoul-hub-delivery' AS prefix,
        'Seoul Hub Delivery Manager' AS display_name,
        '10000000-0000-0000-0000-000000000001'::uuid AS hub_id,
        seq_num - 999 AS delivery_seq
    FROM generate_series(1000, 1299) AS seq_num
    UNION ALL
    SELECT
        seq_num AS seq,
        'perf-busan-company-delivery' AS prefix,
        'Busan Company Delivery Manager' AS display_name,
        '10000000-0000-0000-0000-000000000002'::uuid AS hub_id,
        seq_num - 3999 AS delivery_seq
    FROM generate_series(4000, 4299) AS seq_num
    UNION ALL
    SELECT
        seq_num AS seq,
        'perf-incheon-company-delivery' AS prefix,
        'Incheon Company Delivery Manager' AS display_name,
        '10000000-0000-0000-0000-000000000003'::uuid AS hub_id,
        seq_num - 6999 AS delivery_seq
    FROM generate_series(7000, 7299) AS seq_num
) manager_user_seed;

-- 배송 담당자 복원
INSERT INTO user_service.p_delivery_managers (
    user_id, hub_id, type, delivery_sequence, slack_id,
    created_at, created_by, updated_at, updated_by
)
SELECT
    ('50000000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid,
    hub_id,
    manager_type,
    delivery_seq,
    prefix || '-' || lpad(delivery_seq::text, 3, '0'),
    now(),
    'seed',
    now(),
    'seed'
FROM (
    SELECT
        seq_num AS seq,
        'perf-seoul-hub-delivery' AS prefix,
        '10000000-0000-0000-0000-000000000001'::uuid AS hub_id,
        'HUB_DELIVERY' AS manager_type,
        seq_num - 999 AS delivery_seq
    FROM generate_series(1000, 1299) AS seq_num
    UNION ALL
    SELECT
        seq_num AS seq,
        'perf-busan-company-delivery' AS prefix,
        '10000000-0000-0000-0000-000000000002'::uuid AS hub_id,
        'COMPANY_DELIVERY' AS manager_type,
        seq_num - 3999 AS delivery_seq
    FROM generate_series(4000, 4299) AS seq_num
    UNION ALL
    SELECT
        seq_num AS seq,
        'perf-incheon-company-delivery' AS prefix,
        '10000000-0000-0000-0000-000000000003'::uuid AS hub_id,
        'COMPANY_DELIVERY' AS manager_type,
        seq_num - 6999 AS delivery_seq
    FROM generate_series(7000, 7299) AS seq_num
) manager_seed;

-- 과거 이력 seed 생성
CREATE TEMP TABLE tmp_delivery_perf_history AS
SELECT
    seq,
    ('64000000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid AS delivery_id,
    ('65000000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid AS order_id,
    CASE
        WHEN seq % 2 = 0 THEN '10000000-0000-0000-0000-000000000002'::uuid
        ELSE '10000000-0000-0000-0000-000000000003'::uuid
    END AS destination_hub_id,
    CASE
        WHEN seq % 18 = 0 THEN '20000000-0000-0000-0000-000000000002'::uuid
        WHEN seq % 2 = 0 THEN ('20000000-0000-0000-0000-' || lpad((10 + (seq % 8))::text, 12, '0'))::uuid
        WHEN seq % 18 = 1 THEN '20000000-0000-0000-0000-000000000003'::uuid
        ELSE ('20000000-0000-0000-0000-' || lpad((20 + (seq % 8))::text, 12, '0'))::uuid
    END AS receiver_company_id,
    CASE
        WHEN seq % 2 = 0 THEN ('50000000-0000-0000-0000-' || lpad((4000 + ((seq - 1) % 300))::text, 12, '0'))::uuid
        ELSE ('50000000-0000-0000-0000-' || lpad((7000 + ((seq - 1) % 300))::text, 12, '0'))::uuid
    END AS company_delivery_manager_id,
    ('50000000-0000-0000-0000-' || lpad((1000 + ((seq - 1) % 300))::text, 12, '0'))::uuid AS hub_delivery_manager_id,
    CASE
        WHEN seq % 8 < 6 THEN 'DELIVERED'
        ELSE 'CANCELLED'
    END AS delivery_status,
    CASE
        WHEN seq % 8 < 6 THEN 'COMPLETED'
        ELSE 'FAILED'
    END AS first_route_status,
    CASE
        WHEN seq % 8 < 6 THEN 'COMPLETED'
        WHEN seq % 2 = 0 THEN 'FAILED'
        ELSE 'SKIPPED'
    END AS second_route_status,
    now() - ((seq % 120) || ' days')::interval AS created_at,
    now() - ((seq % 120) || ' days')::interval AS updated_at,
    now() - ((seq % 120) || ' days')::interval + interval '2 days' AS estimated_arrival_at,
    now() - ((seq % 120) || ' days')::interval + interval '2 days' AS delivered_at,
    now() + ((seq % 72) || ' hours')::interval AS final_departure_deadline,
    now() - ((seq % 120) || ' days')::interval + interval '1 day' AS first_route_processed_at,
    now() - ((seq % 120) || ' days')::interval + interval '2 days' AS second_route_processed_at
FROM generate_series(1, 30000) AS seq;

-- 현재 진행 업무 seed 생성
CREATE TEMP TABLE tmp_delivery_perf_active AS
SELECT
    seq,
    ('64100000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid AS delivery_id,
    ('65100000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid AS order_id,
    CASE
        WHEN seq <= 1800 THEN '10000000-0000-0000-0000-000000000002'::uuid
        ELSE '10000000-0000-0000-0000-000000000003'::uuid
    END AS destination_hub_id,
    CASE
        WHEN seq <= 1800 THEN '20000000-0000-0000-0000-000000000002'::uuid
        ELSE '20000000-0000-0000-0000-000000000003'::uuid
    END AS receiver_company_id,
    CASE
        WHEN seq <= 1800 THEN ('50000000-0000-0000-0000-' || lpad((4000 + ((seq - 1) / 6))::text, 12, '0'))::uuid
        ELSE ('50000000-0000-0000-0000-' || lpad((7000 + ((seq - 1801) / 6))::text, 12, '0'))::uuid
    END AS company_delivery_manager_id,
    ('50000000-0000-0000-0000-' || lpad((1000 + ((seq - 1) % 300))::text, 12, '0'))::uuid AS hub_delivery_manager_id,
    CASE seq % 4
        WHEN 0 THEN 'PENDING'
        WHEN 1 THEN 'HUB_IN_TRANSIT'
        WHEN 2 THEN 'DESTINATION_HUB_ARRIVED'
        ELSE 'OUT_FOR_DELIVERY'
    END AS delivery_status,
    CASE
        WHEN seq % 4 IN (0, 1) THEN CASE WHEN seq % 2 = 0 THEN 'PENDING' ELSE 'IN_TRANSIT' END
        ELSE 'COMPLETED'
    END AS first_route_status,
    CASE
        WHEN seq % 4 = 3 THEN 'IN_TRANSIT'
        ELSE 'PENDING'
    END AS second_route_status,
    now() - ((seq % 12) || ' hours')::interval AS created_at,
    now() - ((seq % 12) || ' hours')::interval AS updated_at,
    now() + (((seq % 24) + 12) || ' hours')::interval AS estimated_arrival_at,
    now() + (((seq % 48) + 24) || ' hours')::interval AS final_departure_deadline
FROM generate_series(1, 3600) AS seq;

-- 과거 배송 적재
INSERT INTO delivery_service.p_deliveries (
    delivery_id,
    version,
    order_id,
    departure_hub_id,
    destination_hub_id,
    receiver_company_id,
    company_delivery_manager_id,
    status,
    delivery_address,
    receiver_name,
    hub_manager_slack_id,
    estimated_arrival_at,
    delivered_at,
    final_departure_deadline,
    created_at,
    created_by,
    updated_at,
    updated_by,
    deleted_at,
    deleted_by
)
SELECT
    delivery_id,
    0,
    order_id,
    '10000000-0000-0000-0000-000000000001'::uuid,
    destination_hub_id,
    receiver_company_id,
    company_delivery_manager_id,
    delivery_status,
    CASE
        WHEN destination_hub_id = '10000000-0000-0000-0000-000000000002'::uuid THEN 'Busan perf history address ' || lpad((seq % 9999)::text, 4, '0')
        ELSE 'Incheon perf history address ' || lpad((seq % 9999)::text, 4, '0')
    END,
    'perf-history-receiver-' || lpad((seq % 1000)::text, 3, '0'),
    'seoul-hub-manager',
    estimated_arrival_at,
    delivered_at,
    final_departure_deadline,
    created_at,
    'seed',
    updated_at,
    'seed',
    null,
    null
FROM tmp_delivery_perf_history;

-- 현재 배송 적재
INSERT INTO delivery_service.p_deliveries (
    delivery_id,
    version,
    order_id,
    departure_hub_id,
    destination_hub_id,
    receiver_company_id,
    company_delivery_manager_id,
    status,
    delivery_address,
    receiver_name,
    hub_manager_slack_id,
    estimated_arrival_at,
    delivered_at,
    final_departure_deadline,
    created_at,
    created_by,
    updated_at,
    updated_by,
    deleted_at,
    deleted_by
)
SELECT
    delivery_id,
    0,
    order_id,
    '10000000-0000-0000-0000-000000000001'::uuid,
    destination_hub_id,
    receiver_company_id,
    company_delivery_manager_id,
    delivery_status,
    CASE
        WHEN destination_hub_id = '10000000-0000-0000-0000-000000000002'::uuid THEN 'Busan perf active address ' || lpad((seq % 9999)::text, 4, '0')
        ELSE 'Incheon perf active address ' || lpad((seq % 9999)::text, 4, '0')
    END,
    'perf-active-receiver-' || lpad((seq % 1000)::text, 3, '0'),
    'seoul-hub-manager',
    estimated_arrival_at,
    null,
    final_departure_deadline,
    created_at,
    'seed',
    updated_at,
    'seed',
    null,
    null
FROM tmp_delivery_perf_active;

-- 과거 경로 이력 적재
INSERT INTO delivery_service.p_delivery_route_histories (
    delivery_route_history_id,
    version,
    delivery_id,
    delivery_manager_id,
    sequence,
    route_type,
    departure_type,
    departure_id,
    arrival_type,
    arrival_id,
    location_name,
    status,
    status_message,
    estimated_distance_km,
    estimated_duration_min,
    actual_distance_km,
    actual_duration_min,
    processed_at,
    created_at,
    created_by,
    updated_at,
    updated_by,
    deleted_at,
    deleted_by
)
SELECT
    ('64200000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid,
    0,
    delivery_id,
    hub_delivery_manager_id,
    1,
    'HUB_TO_HUB',
    'HUB',
    '10000000-0000-0000-0000-000000000001'::uuid,
    'HUB',
    destination_hub_id,
    CASE
        WHEN destination_hub_id = '10000000-0000-0000-0000-000000000002'::uuid THEN 'Seoul -> Busan'
        ELSE 'Seoul -> Incheon'
    END,
    first_route_status,
    null,
    CASE
        WHEN destination_hub_id = '10000000-0000-0000-0000-000000000002'::uuid THEN 325.00
        ELSE 36.00
    END,
    CASE
        WHEN destination_hub_id = '10000000-0000-0000-0000-000000000002'::uuid THEN 260
        ELSE 45
    END,
    CASE
        WHEN destination_hub_id = '10000000-0000-0000-0000-000000000002'::uuid THEN 323.10
        ELSE 35.20
    END,
    CASE
        WHEN destination_hub_id = '10000000-0000-0000-0000-000000000002'::uuid THEN 255
        ELSE 43
    END,
    first_route_processed_at,
    created_at,
    'seed',
    updated_at,
    'seed',
    null::timestamp,
    null::varchar
FROM tmp_delivery_perf_history
UNION ALL
SELECT
    ('64300000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid,
    0,
    delivery_id,
    company_delivery_manager_id,
    2,
    'HUB_TO_COMPANY',
    'HUB',
    destination_hub_id,
    'COMPANY',
    receiver_company_id,
    CASE
        WHEN destination_hub_id = '10000000-0000-0000-0000-000000000002'::uuid THEN 'Busan receiver'
        ELSE 'Incheon receiver'
    END,
    second_route_status,
    CASE
        WHEN second_route_status = 'FAILED' THEN 'historical cancellation'
        WHEN second_route_status = 'SKIPPED' THEN 'historical skip'
        ELSE null
    END,
    CASE
        WHEN destination_hub_id = '10000000-0000-0000-0000-000000000002'::uuid THEN 3.50
        ELSE 4.20
    END,
    CASE
        WHEN destination_hub_id = '10000000-0000-0000-0000-000000000002'::uuid THEN 18
        ELSE 22
    END,
    CASE
        WHEN second_route_status = 'COMPLETED' THEN CASE
            WHEN destination_hub_id = '10000000-0000-0000-0000-000000000002'::uuid THEN 3.20
            ELSE 3.90
        END
        ELSE 0.00
    END,
    CASE
        WHEN second_route_status = 'COMPLETED' THEN CASE
            WHEN destination_hub_id = '10000000-0000-0000-0000-000000000002'::uuid THEN 16
            ELSE 20
        END
        WHEN second_route_status = 'FAILED' THEN 5
        ELSE 0
    END,
    second_route_processed_at,
    created_at,
    'seed',
    updated_at,
    'seed',
    null::timestamp,
    null::varchar
FROM tmp_delivery_perf_history;

-- 현재 경로 이력 적재
INSERT INTO delivery_service.p_delivery_route_histories (
    delivery_route_history_id,
    version,
    delivery_id,
    delivery_manager_id,
    sequence,
    route_type,
    departure_type,
    departure_id,
    arrival_type,
    arrival_id,
    location_name,
    status,
    status_message,
    estimated_distance_km,
    estimated_duration_min,
    actual_distance_km,
    actual_duration_min,
    processed_at,
    created_at,
    created_by,
    updated_at,
    updated_by,
    deleted_at,
    deleted_by
)
SELECT
    ('64400000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid,
    0,
    delivery_id,
    hub_delivery_manager_id,
    1,
    'HUB_TO_HUB',
    'HUB',
    '10000000-0000-0000-0000-000000000001'::uuid,
    'HUB',
    destination_hub_id,
    CASE
        WHEN destination_hub_id = '10000000-0000-0000-0000-000000000002'::uuid THEN 'Seoul -> Busan'
        ELSE 'Seoul -> Incheon'
    END,
    first_route_status,
    CASE
        WHEN first_route_status = 'PENDING' THEN 'waiting hub departure'
        WHEN first_route_status = 'IN_TRANSIT' THEN 'hub leg in transit'
        ELSE null
    END,
    CASE
        WHEN destination_hub_id = '10000000-0000-0000-0000-000000000002'::uuid THEN 325.00
        ELSE 36.00
    END,
    CASE
        WHEN destination_hub_id = '10000000-0000-0000-0000-000000000002'::uuid THEN 260
        ELSE 45
    END,
    CASE
        WHEN first_route_status = 'COMPLETED' AND destination_hub_id = '10000000-0000-0000-0000-000000000002'::uuid THEN 324.10
        WHEN first_route_status = 'COMPLETED' THEN 35.80
        ELSE null
    END,
    CASE
        WHEN first_route_status = 'COMPLETED' AND destination_hub_id = '10000000-0000-0000-0000-000000000002'::uuid THEN 257
        WHEN first_route_status = 'COMPLETED' THEN 44
        ELSE null
    END,
    CASE
        WHEN first_route_status = 'COMPLETED' THEN updated_at + interval '1 hour'
        ELSE null
    END,
    created_at,
    'seed',
    updated_at,
    'seed',
    null::timestamp,
    null::varchar
FROM tmp_delivery_perf_active
UNION ALL
SELECT
    ('64500000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid,
    0,
    delivery_id,
    company_delivery_manager_id,
    2,
    'HUB_TO_COMPANY',
    'HUB',
    destination_hub_id,
    'COMPANY',
    receiver_company_id,
    CASE
        WHEN destination_hub_id = '10000000-0000-0000-0000-000000000002'::uuid THEN 'Busan receiver'
        ELSE 'Incheon receiver'
    END,
    second_route_status,
    CASE
        WHEN second_route_status = 'IN_TRANSIT' THEN 'last mile in transit'
        ELSE 'waiting company departure'
    END,
    CASE
        WHEN destination_hub_id = '10000000-0000-0000-0000-000000000002'::uuid THEN 3.50
        ELSE 4.20
    END,
    CASE
        WHEN destination_hub_id = '10000000-0000-0000-0000-000000000002'::uuid THEN 18
        ELSE 22
    END,
    null,
    null,
    null,
    created_at,
    'seed',
    updated_at,
    'seed',
    null::timestamp,
    null::varchar
FROM tmp_delivery_perf_active;

-- Outbox 적재
INSERT INTO delivery_service.p_delivery_outboxes (
    outbox_id,
    topic,
    event_key,
    lo_from_bytea(0, convert_to(payload, 'UTF8')),
    status,
    retry_count,
    published_at,
    last_error,
    created_at,
    created_by,
    updated_at,
    updated_by,
    deleted_at,
    deleted_by
)
SELECT
    ('64600000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid,
    CASE
        WHEN seq % 20 < 16 THEN 'delivery.create.succeed'
        WHEN seq % 20 < 19 THEN 'delivery.create.failed'
        ELSE 'delivery.create.dlq'
    END,
    event_key,
    payload,
    CASE
        WHEN seq % 20 < 15 THEN 'PUBLISHED'
        WHEN seq % 20 < 18 THEN 'FAILED'
        ELSE 'PENDING'
    END,
    CASE
        WHEN seq % 20 < 15 THEN 0
        WHEN seq % 20 < 18 THEN 1 + (seq % 3)
        ELSE 0
    END,
    CASE
        WHEN seq % 20 < 15 THEN created_at + interval '10 minutes'
        ELSE null
    END,
    CASE
        WHEN seq % 20 >= 15 AND seq % 20 < 18 THEN lo_from_bytea(0, convert_to('seed publish failure', 'UTF8'))
        ELSE null
    END,
    created_at,
    'seed',
    updated_at,
    'seed',
    null,
    null
FROM (
    SELECT
        seq,
        order_id::text AS event_key,
        '{"seed":"delivery-perf-baseline","kind":"history","seq":' || seq || '}' AS payload,
        created_at,
        updated_at
    FROM tmp_delivery_perf_history
    UNION ALL
    SELECT
        30000 + seq,
        order_id::text AS event_key,
        '{"seed":"delivery-perf-baseline","kind":"active","seq":' || seq || '}' AS payload,
        created_at,
        updated_at
    FROM tmp_delivery_perf_active
) outbox_seed;

DROP TABLE tmp_delivery_perf_history;
DROP TABLE tmp_delivery_perf_active;

COMMIT;
