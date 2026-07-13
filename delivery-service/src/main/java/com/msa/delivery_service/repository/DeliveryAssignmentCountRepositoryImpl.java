package com.msa.delivery_service.repository;

import com.msa.delivery_service.enums.DeliveryAssignmentType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class DeliveryAssignmentCountRepositoryImpl implements DeliveryAssignmentCountBulkRepository {

    /*
        SKIP LOCKED 적용 전 후보 전체 row lock 조회 쿼리

        select manager_id as "managerId",
               active_assignment_count as "assignmentCount"
        from delivery_service.p_delivery_assignment_counts
        where assignment_type = :assignmentType
          and manager_id in (:managerIds)
        order by manager_id
        for update

        SKIP LOCKED 단독 적용 쿼리

        select manager_id as "managerId",
               active_assignment_count as "assignmentCount"
        from delivery_service.p_delivery_assignment_counts
        where assignment_type = :assignmentType
          and manager_id in (:managerIds)
        order by manager_id
        for update skip locked
    */
    private static final String RESERVE_ASSIGNMENT_SQL = """
        with candidate as (
            select assignment_count.manager_id
            from delivery_service.p_delivery_assignment_counts assignment_count
            where assignment_count.assignment_type = ?
              and assignment_count.manager_id = any (?)
              and assignment_count.active_assignment_count < ?
            order by assignment_count.active_assignment_count
            limit 1
            for update of assignment_count skip locked
        )
        update delivery_service.p_delivery_assignment_counts assignment_count
        set active_assignment_count = assignment_count.active_assignment_count + 1
        from candidate
        where assignment_count.manager_id = candidate.manager_id
          and assignment_count.assignment_type = ?
        returning assignment_count.manager_id
    """;

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
    public Optional<UUID> reserveAssignment(
            Collection<UUID> managerIds,
            DeliveryAssignmentType assignmentType,
            long maxActiveAssignments
    ) {
        if (managerIds.isEmpty()) {
            return Optional.empty();
        }

        PreparedStatementCreator preparedStatementCreator = connection -> {
            PreparedStatement statement = connection.prepareStatement(RESERVE_ASSIGNMENT_SQL);
            statement.setString(1, assignmentType.name());
            statement.setArray(2, connection.createArrayOf("uuid", managerIds.toArray(UUID[]::new)));
            statement.setLong(3, maxActiveAssignments);
            statement.setString(4, assignmentType.name());
            return statement;
        };
        return jdbcTemplate.query(
                preparedStatementCreator,
                resultSet -> resultSet.next()
                        ? Optional.of(resultSet.getObject("manager_id", UUID.class))
                        : Optional.empty()
        );
    }

    @Override
    public void increaseAssignmentCounts(Map<UUID, Long> deltas, DeliveryAssignmentType assignmentType) {
        if (deltas.isEmpty()) {
            return;
        }

        Object[] managerIds = deltas.keySet().toArray(UUID[]::new);
        Object[] assignmentTypes = deltas.keySet().stream()
                .map(ignored -> assignmentType.name())
                .toArray(String[]::new);
        Object[] counts = deltas.values().stream()
                .map(Long::valueOf)
                .toArray(Long[]::new);

        executeBulkIncrease(managerIds, assignmentTypes, counts);
    }

    @Override
    public void increaseAssignmentCounts(Map<DeliveryAssignmentType, Map<UUID, Long>> deltasByAssignmentType) {
        if (deltasByAssignmentType.isEmpty()) {
            return;
        }

        List<UUID> managerIds = new ArrayList<>();
        List<String> assignmentTypes = new ArrayList<>();
        List<Long> counts = new ArrayList<>();

        for (Map.Entry<DeliveryAssignmentType, Map<UUID, Long>> entry : deltasByAssignmentType.entrySet()) {
            DeliveryAssignmentType assignmentType = entry.getKey();
            for (Map.Entry<UUID, Long> delta : entry.getValue().entrySet()) {
                managerIds.add(delta.getKey());
                assignmentTypes.add(assignmentType.name());
                counts.add(delta.getValue());
            }
        }

        if (managerIds.isEmpty()) {
            return;
        }

        executeBulkIncrease(
                managerIds.toArray(UUID[]::new),
                assignmentTypes.toArray(String[]::new),
                counts.toArray(Long[]::new)
        );
    }

    private void executeBulkIncrease(
            Object[] managerIds,
            Object[] assignmentTypes,
            Object[] counts
    ) {
        PreparedStatementCreator preparedStatementCreator = connection -> {
            PreparedStatement statement = connection.prepareStatement(BULK_INCREASE_SQL);
            statement.setArray(1, connection.createArrayOf("uuid", managerIds));
            statement.setArray(2, connection.createArrayOf("varchar", assignmentTypes));
            statement.setArray(3, connection.createArrayOf("bigint", counts));
            return statement;
        };
        jdbcTemplate.update(preparedStatementCreator);
    }
}
