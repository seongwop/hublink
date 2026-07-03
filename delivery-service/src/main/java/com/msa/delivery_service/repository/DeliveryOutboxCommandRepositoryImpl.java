package com.msa.delivery_service.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class DeliveryOutboxCommandRepositoryImpl implements DeliveryOutboxCommandRepository {

    private static final int LAST_ERROR_MAX_LENGTH = 1000;

    private static final String INSERT_PENDING_SQL = """
        insert into delivery_service.p_delivery_outboxes (
            outbox_id,
            topic,
            event_key,
            payload,
            status,
            retry_count,
            created_at,
            updated_at
        )
        values (?, ?, ?, ?, 'PENDING', 0, now(), now())
        on conflict (topic, event_key) do nothing
    """;

    private static final String MARK_PUBLISHED_SQL = """
        update delivery_service.p_delivery_outboxes
        set status = 'PUBLISHED',
            published_at = now(),
            last_error = null,
            updated_at = now()
        where outbox_id = ?
    """;

    private static final String MARK_FAILED_SQL = """
        update delivery_service.p_delivery_outboxes
        set status = 'FAILED',
            retry_count = retry_count + 1,
            last_error = ?,
            updated_at = now()
        where outbox_id = ?
    """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public UUID insertPending(String topic, String eventKey, String payload) {
        UUID outboxId = UUID.randomUUID();
        int inserted = jdbcTemplate.update(
                INSERT_PENDING_SQL,
                outboxId,
                topic,
                eventKey,
                payload
        );
        return inserted == 0 ? null : outboxId;
    }

    @Override
    public void markPublished(UUID outboxId) {
        jdbcTemplate.update(MARK_PUBLISHED_SQL, outboxId);
    }

    @Override
    public void markFailed(UUID outboxId, String errorMessage) {
        jdbcTemplate.update(MARK_FAILED_SQL, truncate(errorMessage), outboxId);
    }

    private String truncate(String errorMessage) {
        if (errorMessage == null || errorMessage.length() <= LAST_ERROR_MAX_LENGTH) {
            return errorMessage;
        }
        return errorMessage.substring(0, LAST_ERROR_MAX_LENGTH);
    }
}
