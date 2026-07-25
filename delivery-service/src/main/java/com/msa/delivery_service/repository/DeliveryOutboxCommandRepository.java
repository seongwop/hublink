package com.msa.delivery_service.repository;

import java.util.List;
import java.util.UUID;

public interface DeliveryOutboxCommandRepository {

    UUID insertPending(String topic, String eventKey, String payload);

    void markPublished(List<UUID> outboxIds);

    void markFailed(UUID outboxId, String errorMessage);
}
