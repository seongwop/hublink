-- Delivery JPA repository 기준 EXPLAIN ANALYZE 모음
-- JPQL / derived query를 PostgreSQL SQL 형태로 펼친 버전

-- 1. DeliveryRepository.existsByOrderId
EXPLAIN (ANALYZE, BUFFERS)
SELECT 1
FROM delivery_service.p_deliveries d
WHERE d.order_id = '61000000-0000-0000-0000-000000000001'::uuid
  AND d.deleted_at IS NULL
LIMIT 1;

-- 2. DeliveryRepository.findByOrderId
EXPLAIN (ANALYZE, BUFFERS)
SELECT *
FROM delivery_service.p_deliveries d
WHERE d.order_id = '61000000-0000-0000-0000-000000000001'::uuid
  AND d.deleted_at IS NULL
LIMIT 1;

-- 3. DeliveryRepository.findAllByCompanyDeliveryManagerId
EXPLAIN (ANALYZE, BUFFERS)
SELECT *
FROM delivery_service.p_deliveries d
WHERE d.company_delivery_manager_id = '50000000-0000-0000-0000-000000004000'::uuid
  AND d.deleted_at IS NULL
ORDER BY d.created_at DESC
LIMIT 20;

-- 4. DeliveryRepository.countActiveAssignmentsByManagerIds
EXPLAIN (ANALYZE, BUFFERS)
SELECT
    d.company_delivery_manager_id AS manager_id,
    count(*) AS assignment_count
FROM delivery_service.p_deliveries d
WHERE d.company_delivery_manager_id IN (
    SELECT ('50000000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid
    FROM generate_series(4000, 4119) AS seq
)
  AND d.deleted_at IS NULL
  AND d.status NOT IN ('DELIVERED', 'CANCELLED')
GROUP BY d.company_delivery_manager_id;

-- 5. DeliveryRouteHistoryRepository.existsByDeliveryDeliveryIdAndDeliveryManagerId
EXPLAIN (ANALYZE, BUFFERS)
SELECT 1
FROM delivery_service.p_delivery_route_histories rh
WHERE rh.delivery_id = '60000000-0000-0000-0000-000000000001'::uuid
  AND rh.delivery_manager_id = '50000000-0000-0000-0000-000000001000'::uuid
  AND rh.deleted_at IS NULL
LIMIT 1;

-- 6. DeliveryRouteHistoryRepository.findByDeliveryDeliveryIdOrderBySequenceAsc
EXPLAIN (ANALYZE, BUFFERS)
SELECT *
FROM delivery_service.p_delivery_route_histories rh
WHERE rh.delivery_id = '60000000-0000-0000-0000-000000000001'::uuid
  AND rh.deleted_at IS NULL
ORDER BY rh.sequence ASC;

-- 7. DeliveryRouteHistoryRepository.countActiveAssignmentsByManagerIds
EXPLAIN (ANALYZE, BUFFERS)
SELECT
    rh.delivery_manager_id AS manager_id,
    count(*) AS assignment_count
FROM delivery_service.p_delivery_route_histories rh
WHERE rh.delivery_manager_id IN (
    SELECT ('50000000-0000-0000-0000-' || lpad(seq::text, 12, '0'))::uuid
    FROM generate_series(1000, 1119) AS seq
)
  AND rh.deleted_at IS NULL
  AND rh.status NOT IN ('COMPLETED', 'SKIPPED', 'FAILED')
GROUP BY rh.delivery_manager_id;

-- 8. DeliveryOutboxRepository.findTop100ByStatusInAndRetryCountLessThanOrderByCreatedAtAsc
EXPLAIN (ANALYZE, BUFFERS)
SELECT *
FROM delivery_service.p_delivery_outboxes o
WHERE o.status IN ('PENDING', 'FAILED')
  AND o.retry_count < 3
  AND o.deleted_at IS NULL
ORDER BY o.created_at ASC
LIMIT 100;

-- 9. DeliveryOutboxRepository.existsByTopicAndEventKey
EXPLAIN (ANALYZE, BUFFERS)
SELECT 1
FROM delivery_service.p_delivery_outboxes o
WHERE o.topic = 'delivery.create.succeed'
  AND o.event_key = '61000000-0000-0000-0000-000000000001'
  AND o.deleted_at IS NULL
LIMIT 1;
