package com.msa.core_common.stream;

public final class DeadlineStreamConstants {
    private DeadlineStreamConstants() {
    }

    // Stream
    public static final String DEADLINE_REQUESTED_STREAM = "deadline:requested:stream";
    public static final String DEADLINE_GENERATED_STREAM = "deadline:generated:stream";
    public static final String DEADLINE_GENERATED_DELIVERY_DLQ_STREAM = "deadline:generated:delivery:dlq:stream";

    // Consumer group
    public static final String AI_SERVICE_GROUP = "ai-service-group";
    public static final String SLACK_SERVICE_GROUP = "slack-service-group";
    public static final String DELIVERY_SERVICE_GROUP = "delivery-service-group";

    // Consumer
    public static final String AI_SERVICE_CONSUMER = "ai-service";
    public static final String SLACK_SERVICE_CONSUMER = "slack-service";
    public static final String DELIVERY_SERVICE_CONSUMER = "delivery-service";


    public static String aiServiceConsumerName(int index) {
        return AI_SERVICE_CONSUMER + "-" + index;
    }
}
