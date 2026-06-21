package com.msa.delivery_service.repository;

import com.msa.delivery_service.enums.DeliveryAssignmentType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class DeliveryAssignmentCountRepositoryImpl implements DeliveryAssignmentCountBulkRepository {

    private static final String BULK_INCREASE_SQL = """
        insert into delivery_service.p_delivery_assignment_counts (
            manager_id,
            assignment_type,
            active_assignment_count
        )
        select
            batch.manager_id,
            batch.assignment_type,
            batch.delta
        from unnest(
            ?::uuid[],
            ?::varchar[],
            ?::bigint[]
        ) as batch(manager_id, assignment_type, delta)
        on conflict (manager_id, assignment_type)
        do update set active_assignment_count =
            delivery_service.p_delivery_assignment_counts.active_assignment_count
            + excluded.active_assignment_count
    """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void increaseAssignmentCounts(Map<UUID, Long> deltas, DeliveryAssignmentType assignmentType) {
        if (deltas.isEmpty()) {
            return;
        }

        PreparedStatementCreator preparedStatementCreator = connection -> {
            PreparedStatement statement = connection.prepareStatement(BULK_INCREASE_SQL);
            Object[] managerIds = deltas.keySet().toArray(UUID[]::new);
            Object[] assignmentTypes = deltas.keySet().stream()
                    .map(ignored -> assignmentType.name())
                    .toArray(String[]::new);
            Object[] counts = deltas.values().stream()
                    .map(Long::valueOf)
                    .toArray(Long[]::new);

            statement.setArray(1, connection.createArrayOf("uuid", managerIds));
            statement.setArray(2, connection.createArrayOf("varchar", assignmentTypes));
            statement.setArray(3, connection.createArrayOf("bigint", counts));
            return statement;
        };
        jdbcTemplate.update(preparedStatementCreator);
    }
}
