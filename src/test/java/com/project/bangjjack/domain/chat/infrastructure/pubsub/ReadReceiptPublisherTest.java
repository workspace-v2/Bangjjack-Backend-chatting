package com.project.bangjjack.domain.chat.infrastructure.pubsub;

import com.project.bangjjack.domain.chat.application.dto.response.ReadReceiptBroadcast;
import com.project.bangjjack.global.infrastructure.JsonSerializer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.codec.StringCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReadReceiptPublisher")
class ReadReceiptPublisherTest {

    private static final Long ROOM_ID = 1L;
    private static final String CHANNEL = "chat:read-receipt:" + ROOM_ID;
    private static final String JSON = "{\"readerId\":2}";

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private JsonSerializer jsonSerializer;

    @Mock
    private RTopic topic;

    private MeterRegistry meterRegistry;
    private ReadReceiptPublisher publisher;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        publisher = new ReadReceiptPublisher(redissonClient, jsonSerializer, meterRegistry);
    }

    @Test
    @DisplayName("성공: 읽음 채널로 발행하고 실패 카운터는 증가하지 않는다")
    void publish_success() {
        ReadReceiptBroadcast broadcast = ReadReceiptBroadcast.of(ROOM_ID, 2L, 10L);
        given(jsonSerializer.serialize(broadcast)).willReturn(JSON);
        given(redissonClient.getTopic(CHANNEL, StringCodec.INSTANCE)).willReturn(topic);

        publisher.publish(ROOM_ID, broadcast);

        then(topic).should().publish(JSON);
        assertThat(failureCount()).isZero();
    }

    @Test
    @DisplayName("실패: Redis 발행 실패 시 예외를 전파하지 않고 실패 카운터를 증가시킨다")
    void publish_failure_incrementsCounter() {
        ReadReceiptBroadcast broadcast = ReadReceiptBroadcast.of(ROOM_ID, 2L, 10L);
        given(jsonSerializer.serialize(broadcast)).willReturn(JSON);
        given(redissonClient.getTopic(CHANNEL, StringCodec.INSTANCE)).willReturn(topic);
        given(topic.publish(JSON)).willThrow(new RedisConnectionException("connection refused"));

        assertThatCode(() -> publisher.publish(ROOM_ID, broadcast)).doesNotThrowAnyException();

        assertThat(failureCount()).isEqualTo(1.0);
    }

    private double failureCount() {
        return meterRegistry.get(PubSubMetrics.PUBLISH_FAILURE_METRIC)
                .tag(PubSubMetrics.CHANNEL_TAG, PubSubMetrics.CHANNEL_READ_RECEIPT)
                .counter()
                .count();
    }
}
