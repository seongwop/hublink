package com.msa.order_service.service;

import com.msa.order_service.entity.Outbox;
import com.msa.order_service.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Component
@Slf4j
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OrderService orderService;

    @Scheduled(fixedDelay = 1000)
    public void publishPendingEvents() {
        List<Outbox> pendingList = outboxRepository.findByProcessedFalse();
        if(pendingList.isEmpty()) return;

        for (Outbox outbox : pendingList) {
            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(outbox.getTopic(), outbox.getAggregateId(), outbox.getPayload());

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.debug("event=ORDER_OUTBOX_PUBLISH_CONFIRMED outboxId={} offset={}",
                            outbox.getId(), result.getRecordMetadata().offset());

                    log.info("event=ORDER_OUTBOX_PUBLISHED outboxId={} topic={} aggregateId={} partition={} offset={}",
                            outbox.getId(),
                            outbox.getTopic(),
                            outbox.getAggregateId(),
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset()
                    );
                    orderService.markOutboxProcessed(outbox.getId());
                } else {
                    log.error("event=ORDER_OUTBOX_PUBLISH_FAILED outboxId={} topic={} aggregateId={}",
                            outbox.getId(),
                            outbox.getTopic(),
                            outbox.getAggregateId(),
                            ex
                    );
                    log.error("event=ORDER_OUTBOX_PUBLISH_EXCEPTION outboxId={}", outbox.getId(), ex);
                }
            });
        }
    }


}
