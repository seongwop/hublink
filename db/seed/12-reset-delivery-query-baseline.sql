BEGIN;

-- 배송 대용량 쿼리 기준선 복원
ALTER TABLE delivery_service.p_delivery_outboxes
    ALTER COLUMN payload TYPE text USING payload::text,
    ALTER COLUMN last_error TYPE text USING last_error::text;

TRUNCATE TABLE
    delivery_service.p_delivery_route_histories,
    delivery_service.p_deliveries,
    delivery_service.p_delivery_outboxes
RESTART IDENTITY CASCADE;

-- delivery 적재
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
    ('60000000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid,
    0,
    ('61000000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid,
    '10000000-0000-0000-0000-000000000001'::uuid,
    CASE
        WHEN seq % 2 = 0 THEN '10000000-0000-0000-0000-000000000002'::uuid
        ELSE '10000000-0000-0000-0000-000000000003'::uuid
    END,
    CASE
        WHEN seq % 18 = 0 THEN '20000000-0000-0000-0000-000000000002'::uuid
        WHEN seq % 2 = 0 THEN ('20000000-0000-0000-0000-' || lpad((10 + (seq % 8))::text, 12, '0'))::uuid
        WHEN seq % 18 = 1 THEN '20000000-0000-0000-0000-000000000003'::uuid
        ELSE ('20000000-0000-0000-0000-' || lpad((20 + (seq % 8))::text, 12, '0'))::uuid
    END,
    CASE
        WHEN seq % 2 = 0 THEN ('50000000-0000-0000-0000-' || lpad((4000 + (seq % 120))::text, 12, '0'))::uuid
        ELSE ('50000000-0000-0000-0000-' || lpad((7000 + (seq % 120))::text, 12, '0'))::uuid
    END,
    CASE
        WHEN seq % 20 < 14 THEN 'DELIVERED'
        WHEN seq % 20 < 18 THEN 'CANCELLED'
        WHEN seq % 20 = 18 THEN 'PENDING'
        WHEN seq % 20 = 19 AND seq % 4 = 0 THEN 'HUB_IN_TRANSIT'
        WHEN seq % 20 = 19 AND seq % 4 = 1 THEN 'DESTINATION_HUB_ARRIVED'
        ELSE 'OUT_FOR_DELIVERY'
    END,
    CASE
        WHEN seq % 2 = 0 THEN 'Busan load-test address ' || lpad((seq % 9999)::text, 4, '0')
        ELSE 'Incheon load-test address ' || lpad((seq % 9999)::text, 4, '0')
    END,
    'query-baseline-receiver-' || lpad((seq % 1000)::text, 3, '0'),
    'seoul-hub-manager',
    now() - ((seq % 90) || ' days')::interval + interval '2 days',
    CASE
        WHEN seq % 20 < 14 THEN now() - ((seq % 60) || ' days')::interval
        ELSE null
    END,
    now() + ((seq % 72) || ' hours')::interval,
    now() - ((seq % 120) || ' days')::interval,
    'seed',
    now() - ((seq % 120) || ' days')::interval,
    'seed',
    CASE
        WHEN seq % 25 = 0 AND seq % 20 < 18 THEN now() - ((seq % 30) || ' days')::interval
        ELSE null
    END,
    CASE
        WHEN seq % 25 = 0 AND seq % 20 < 18 THEN 'seed'
        ELSE null
    END
FROM generate_series(1, 36000) AS seq;

-- route history 적재
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
    ('62000000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid,
    0,
    ('60000000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid,
    ('50000000-0000-0000-0000-' || lpad((1000 + (seq % 120))::text, 12, '0'))::uuid,
    1,
    'HUB_TO_HUB',
    'HUB',
    '10000000-0000-0000-0000-000000000001'::uuid,
    'HUB',
    CASE
        WHEN seq % 2 = 0 THEN '10000000-0000-0000-0000-000000000002'::uuid
        ELSE '10000000-0000-0000-0000-000000000003'::uuid
    END,
    CASE
        WHEN seq % 2 = 0 THEN 'Seoul -> Busan'
        ELSE 'Seoul -> Incheon'
    END,
    CASE
        WHEN seq % 20 < 14 THEN 'COMPLETED'
        WHEN seq % 20 < 18 THEN 'FAILED'
        WHEN seq % 20 = 18 THEN 'PENDING'
        WHEN seq % 20 = 19 AND seq % 4 = 0 THEN 'IN_TRANSIT'
        ELSE 'COMPLETED'
    END,
    CASE
        WHEN seq % 20 < 18 THEN null
        WHEN seq % 20 = 18 THEN 'waiting hub departure'
        WHEN seq % 20 = 19 AND seq % 4 = 0 THEN 'in transit to destination hub'
        ELSE null
    END,
    CASE
        WHEN seq % 2 = 0 THEN 325.00
        ELSE 36.00
    END,
    CASE
        WHEN seq % 2 = 0 THEN 260
        ELSE 45
    END,
    CASE
        WHEN seq % 20 < 14 THEN CASE WHEN seq % 2 = 0 THEN 323.10 ELSE 35.20 END
        WHEN seq % 20 < 18 THEN CASE WHEN seq % 2 = 0 THEN 120.00 ELSE 18.50 END
        ELSE null
    END,
    CASE
        WHEN seq % 20 < 14 THEN CASE WHEN seq % 2 = 0 THEN 255 ELSE 43 END
        WHEN seq % 20 < 18 THEN CASE WHEN seq % 2 = 0 THEN 98 ELSE 21 END
        ELSE null
    END,
    CASE
        WHEN seq % 20 < 18 OR (seq % 20 = 19 AND seq % 4 <> 0) THEN now() - ((seq % 90) || ' days')::interval + interval '1 day'
        ELSE null
    END,
    now() - ((seq % 120) || ' days')::interval,
    'seed',
    now() - ((seq % 120) || ' days')::interval,
    'seed',
    CASE
        WHEN seq % 25 = 0 AND seq % 20 < 18 THEN now() - ((seq % 30) || ' days')::interval
        ELSE null
    END,
    CASE
        WHEN seq % 25 = 0 AND seq % 20 < 18 THEN 'seed'
        ELSE null
    END
FROM generate_series(1, 36000) AS seq

UNION ALL

SELECT
    ('63000000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid,
    0,
    ('60000000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid,
    CASE
        WHEN seq % 2 = 0 THEN ('50000000-0000-0000-0000-' || lpad((4000 + (seq % 120))::text, 12, '0'))::uuid
        ELSE ('50000000-0000-0000-0000-' || lpad((7000 + (seq % 120))::text, 12, '0'))::uuid
    END,
    2,
    'HUB_TO_COMPANY',
    'HUB',
    CASE
        WHEN seq % 2 = 0 THEN '10000000-0000-0000-0000-000000000002'::uuid
        ELSE '10000000-0000-0000-0000-000000000003'::uuid
    END,
    'COMPANY',
    CASE
        WHEN seq % 18 = 0 THEN '20000000-0000-0000-0000-000000000002'::uuid
        WHEN seq % 2 = 0 THEN ('20000000-0000-0000-0000-' || lpad((10 + (seq % 8))::text, 12, '0'))::uuid
        WHEN seq % 18 = 1 THEN '20000000-0000-0000-0000-000000000003'::uuid
        ELSE ('20000000-0000-0000-0000-' || lpad((20 + (seq % 8))::text, 12, '0'))::uuid
    END,
    CASE
        WHEN seq % 2 = 0 THEN 'Busan receiver'
        ELSE 'Incheon receiver'
    END,
    CASE
        WHEN seq % 20 < 14 THEN 'COMPLETED'
        WHEN seq % 20 < 18 THEN CASE WHEN seq % 3 = 0 THEN 'FAILED' ELSE 'SKIPPED' END
        WHEN seq % 20 = 18 THEN 'PENDING'
        WHEN seq % 20 = 19 AND seq % 4 = 0 THEN 'PENDING'
        WHEN seq % 20 = 19 AND seq % 4 = 1 THEN 'PENDING'
        ELSE 'IN_TRANSIT'
    END,
    CASE
        WHEN seq % 20 < 18 THEN null
        WHEN seq % 20 = 18 THEN 'waiting company assignment'
        WHEN seq % 20 = 19 AND seq % 4 IN (0, 1) THEN 'waiting final leg'
        ELSE 'out for delivery'
    END,
    CASE
        WHEN seq % 2 = 0 THEN 3.50
        ELSE 4.20
    END,
    CASE
        WHEN seq % 2 = 0 THEN 18
        ELSE 22
    END,
    CASE
        WHEN seq % 20 < 14 THEN CASE WHEN seq % 2 = 0 THEN 3.20 ELSE 3.90 END
        WHEN seq % 20 < 18 THEN 0.00
        ELSE null
    END,
    CASE
        WHEN seq % 20 < 14 THEN CASE WHEN seq % 2 = 0 THEN 16 ELSE 20 END
        WHEN seq % 20 < 18 THEN CASE WHEN seq % 3 = 0 THEN 5 ELSE 0 END
        ELSE null
    END,
    CASE
        WHEN seq % 20 < 18 THEN now() - ((seq % 90) || ' days')::interval + interval '2 days'
        ELSE null
    END,
    now() - ((seq % 120) || ' days')::interval,
    'seed',
    now() - ((seq % 120) || ' days')::interval,
    'seed',
    CASE
        WHEN seq % 25 = 0 AND seq % 20 < 18 THEN now() - ((seq % 30) || ' days')::interval
        ELSE null
    END,
    CASE
        WHEN seq % 25 = 0 AND seq % 20 < 18 THEN 'seed'
        ELSE null
    END
FROM generate_series(1, 36000) AS seq;

-- outbox 적재
INSERT INTO delivery_service.p_delivery_outboxes (
    outbox_id,
    topic,
    event_key,
    payload,
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
    ('64000000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid,
    CASE
        WHEN seq % 20 < 16 THEN 'delivery.create.succeed'
        WHEN seq % 20 < 19 THEN 'delivery.create.failed'
        ELSE 'delivery.create.dlq'
    END,
    ('61000000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid::text,
    '{"seed":"delivery-query-baseline","seq":' || seq || '}',
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
        WHEN seq % 20 < 15 THEN now() - ((seq % 90) || ' days')::interval
        ELSE null
    END,
    CASE
        WHEN seq % 20 < 18 AND seq % 20 >= 15 THEN 'seed publish failure'
        ELSE null
    END,
    now() - ((seq % 120) || ' days')::interval,
    'seed',
    now() - ((seq % 120) || ' days')::interval,
    'seed',
    null,
    null
FROM generate_series(1, 36000) AS seq;

COMMIT;
