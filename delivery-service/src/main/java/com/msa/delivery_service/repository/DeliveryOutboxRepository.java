package com.msa.delivery_service.repository;

import com.msa.delivery_service.entity.DeliveryOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DeliveryOutboxRepository extends JpaRepository<DeliveryOutbox, UUID> {

    boolean existsByTopicAndEventKey(String topic, String eventKey);

    List<DeliveryOutbox> findTop100ByStatusInAndRetryCountLessThanOrderByCreatedAtAsc(
            Collection<DeliveryOutbox.Status> statuses,
            int retryCount
    );
}
