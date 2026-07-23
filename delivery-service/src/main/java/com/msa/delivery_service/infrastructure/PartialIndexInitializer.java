package com.msa.delivery_service.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PartialIndexInitializer {
    /*
        Partial unique index 생성을 위해 서버가 시작될 때 네이티브 쿼리 수행
        1. 배송 생성 제약
        2. 업체 배송 담당자 배정 제약
        3. 허브 배송 담당자 배정 제약
        4. Outbox 발행 대상 조회
    */

    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    /*
        단일 인스턴스 환경에서만 사용 가능
        확장성 고려 시 마이그레이션 적용 및 배포 단계에서 처리 요망
    */
    public void initializeIndexes() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS delivery_service.p_delivery_assignment_counts (
                    manager_id uuid NOT NULL,
                    assignment_type varchar(50) NOT NULL,
                    active_assignment_count bigint NOT NULL DEFAULT 0,
                    PRIMARY KEY (manager_id, assignment_type)
                )
                """);

        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uk_p_deliveries_active_order_id
                ON delivery_service.p_deliveries (order_id)
                WHERE deleted_at IS NULL
                """);

        jdbcTemplate.execute("""
                DROP INDEX IF EXISTS delivery_service.uk_p_deliveries_active_company_delivery_manager
                """);

        jdbcTemplate.execute("""
                DROP INDEX IF EXISTS delivery_service.uk_p_delivery_route_histories_active_delivery_manager
                """);

        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_p_deliveries_active_company_delivery_manager
                ON delivery_service.p_deliveries (company_delivery_manager_id)
                WHERE deleted_at IS NULL
                  AND status NOT IN ('DELIVERED', 'CANCELLED')
                """);

        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_p_delivery_route_histories_active_delivery_manager
                ON delivery_service.p_delivery_route_histories (delivery_manager_id)
                WHERE deleted_at IS NULL
                  AND status NOT IN ('COMPLETED', 'SKIPPED', 'FAILED')
                """);

        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_p_delivery_assignment_counts_type_manager
                ON delivery_service.p_delivery_assignment_counts (assignment_type, manager_id)
                """);

        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_delivery_outbox_publishable_created_at
                ON delivery_service.p_delivery_outboxes (created_at, outbox_id)
                WHERE status IN ('PENDING', 'FAILED')
                  AND retry_count < 5
                """);
    }
}
