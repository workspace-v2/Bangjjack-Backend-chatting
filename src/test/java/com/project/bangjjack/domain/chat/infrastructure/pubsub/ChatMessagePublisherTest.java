package com.project.bangjjack.domain.chat.infrastructure.pubsub;

import com.project.bangjjack.domain.chat.application.dto.response.ChatMessageBroadcast;
import com.project.bangjjack.domain.chat.application.dto.response.WebSocketBroadcastType;
import com.project.bangjjack.domain.chat.domain.entity.MessageType;
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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatMessagePublisher")
class ChatMessagePublisherTest {

    private static final Long ROOM_ID = 1L;
    private static final String CHANNEL = "chat:room:" + ROOM_ID;
    private static final String JSON = "{\"messageId\":10}";

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private JsonSerializer jsonSerializer;

    @Mock
    private RTopic topic;

    private MeterRegistry meterRegistry;
    private ChatMessagePublisher publisher;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        publisher = new ChatMessagePublisher(redissonClient, jsonSerializer, meterRegistry);
    }

    @Test
    @DisplayName("성공: 방 채널로 메시지를 발행하고 실패 카운터는 증가하지 않는다")
    void publish_success() {
        ChatMessageBroadcast broadcast = broadcast();
        given(jsonSerializer.serialize(broadcast)).willReturn(JSON);
        given(redissonClient.getTopic(CHANNEL, StringCodec.INSTANCE)).willReturn(topic);

        publisher.publish(ROOM_ID, broadcast);

        then(topic).should().publish(JSON);
        assertThat(failureCount()).isZero();
    }

    @Test
    @DisplayName("실패: Redis 발행 실패 시 예외를 전파하지 않고 실패 카운터를 증가시킨다 (로컬 Fallback 없음)")
    void publish_failure_incrementsCounter() {
        ChatMessageBroadcast broadcast = broadcast();
        given(jsonSerializer.serialize(broadcast)).willReturn(JSON);
        given(redissonClient.getTopic(CHANNEL, StringCodec.INSTANCE)).willReturn(topic);
        given(topic.publish(JSON)).willThrow(new RedisConnectionException("connection refused"));

        assertThatCode(() -> publisher.publish(ROOM_ID, broadcast)).doesNotThrowAnyException();

        assertThat(failureCount()).isEqualTo(1.0);
    }

    private double failureCount() {
        return meterRegistry.get(PubSubMetrics.PUBLISH_FAILURE_METRIC)
                .tag(PubSubMetrics.CHANNEL_TAG, PubSubMetrics.CHANNEL_CHAT_MESSAGE)
                .counter()
                .count();
    }

    private ChatMessageBroadcast broadcast() {
        return new ChatMessageBroadcast(
                WebSocketBroadcastType.CHAT_MESSAGE, 10L, ROOM_ID, 2L, "안녕하세요",
                MessageType.USER, null, LocalDateTime.of(2026, 10, 31, 12, 0));
    }
}
