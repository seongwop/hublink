package com.msa.delivery_service.repository;

import java.util.UUID;

public interface DeliveryOutboxCommandRepository {

    UUID insertPending(String topic, String eventKey, String payload);

    void markPublished(UUID outboxId);

    void markFailed(UUID outboxId, String errorMessage);
}
