package com.project.bangjjack.domain.chat.infrastructure.event;

import com.project.bangjjack.domain.chat.application.event.ChatRoomCreatedEvent;
import com.project.bangjjack.domain.chat.infrastructure.pubsub.PubSubPublishExecutor;
import com.project.bangjjack.global.infrastructure.JsonSerializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatRoomCreatedEventListener {

    private static final String CHANNEL = "chat:system:room-created";

    private final RedissonClient redissonClient;
    private final JsonSerializer jsonSerializer;
    private final PubSubPublishExecutor publishExecutor;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(ChatRoomCreatedEvent event) {
        // DB 커넥션 반납 전에 Redis 를 기다리지 않도록 발행은 전용 실행기에서 한다.
        publishExecutor.execute(event.roomId(), () -> publish(event));
    }

    private void publish(ChatRoomCreatedEvent event) {
        try {
            String json = jsonSerializer.serialize(event);
            RTopic topic = redissonClient.getTopic(CHANNEL, StringCodec.INSTANCE);
            topic.publish(json);
            log.debug("[Redis Pub/Sub] 채팅방 생성 이벤트 발행 - roomId={}", event.roomId());
        } catch (Exception e) {
            log.error("[Redis Pub/Sub] 채팅방 생성 이벤트 발행 실패 - roomId={}, 원인={}", event.roomId(), e.getMessage());
        }
    }
}
