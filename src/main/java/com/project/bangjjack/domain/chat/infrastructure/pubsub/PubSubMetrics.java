package com.project.bangjjack.domain.chat.infrastructure.pubsub;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Redis Pub/Sub 발행 메트릭.
 * Prometheus 노출명: chat_pubsub_publish_failures_total{channel="..."}
 */
final class PubSubMetrics {

    static final String PUBLISH_FAILURE_METRIC = "chat.pubsub.publish.failures";
    static final String CHANNEL_TAG = "channel";
    static final String CHANNEL_CHAT_MESSAGE = "chat_message";
    static final String CHANNEL_READ_RECEIPT = "read_receipt";

    private PubSubMetrics() {
    }

    static Counter publishFailureCounter(MeterRegistry meterRegistry, String channel) {
        return Counter.builder(PUBLISH_FAILURE_METRIC)
                .description("Redis Pub/Sub 발행 실패 횟수")
                .tag(CHANNEL_TAG, channel)
                .register(meterRegistry);
    }
}
