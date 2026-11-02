package com.project.bangjjack.domain.chat.infrastructure.pubsub;

import com.project.bangjjack.domain.chat.application.dto.response.ChatMessageBroadcast;
import com.project.bangjjack.global.infrastructure.JsonSerializer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

/**
 * 채팅 메시지를 Redis Pub/Sub으로 발행한다.
 *
 * 발행 실패 시 로컬 브로드캐스트(Fallback)를 하지 않는다.
 * Fallback은 현재 서버에 연결된 세션에만 메시지를 전달하므로,
 * 다중 서버 환경에서 같은 방이라도 서버마다 수신 결과가 달라지는 불일치를 만든다.
 * 메시지는 이미 DB에 커밋된 상태이므로, 누락분은 클라이언트의 메시지 조회로 복구한다.
 */
@Slf4j
@Component
public class ChatMessagePublisher {

    private static final String CHANNEL_PREFIX = "chat:room:";

    private final RedissonClient redissonClient;
    private final JsonSerializer jsonSerializer;
    private final Counter publishFailureCounter;

    public ChatMessagePublisher(RedissonClient redissonClient, JsonSerializer jsonSerializer, MeterRegistry meterRegistry) {
        this.redissonClient = redissonClient;
        this.jsonSerializer = jsonSerializer;
        this.publishFailureCounter = PubSubMetrics.publishFailureCounter(meterRegistry, PubSubMetrics.CHANNEL_CHAT_MESSAGE);
    }

    public void publish(Long roomId, ChatMessageBroadcast broadcast) {
        try {
            String json = jsonSerializer.serialize(broadcast);
            RTopic topic = redissonClient.getTopic(CHANNEL_PREFIX + roomId, StringCodec.INSTANCE);
            topic.publish(json);
            log.debug("[Redis Pub/Sub] 메시지 발행 완료 - channel={}, messageId={}", CHANNEL_PREFIX + roomId, broadcast.messageId());
        } catch (Exception e) {
            publishFailureCounter.increment();
            log.error("[Redis Pub/Sub] 메시지 발행 실패 - roomId={}, messageId={}, 원인={}", roomId, broadcast.messageId(), e.getMessage(), e);
        }
    }
}
