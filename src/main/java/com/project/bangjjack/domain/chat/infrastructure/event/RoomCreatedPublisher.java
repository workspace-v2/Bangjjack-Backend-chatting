package com.project.bangjjack.domain.chat.infrastructure.event;

import com.project.bangjjack.domain.chat.application.event.ChatRoomCreatedEvent;
import com.project.bangjjack.global.infrastructure.JsonSerializer;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

/** 방 생성 이벤트 Redis 발행. 즉시 발행(리스너)과 Outbox 폴러가 공유한다. 실패하면 예외를 던진다. */
@Component
@RequiredArgsConstructor
public class RoomCreatedPublisher {

    static final String CHANNEL = "chat:system:room-created";

    private final RedissonClient redissonClient;
    private final JsonSerializer jsonSerializer;

    public void publish(ChatRoomCreatedEvent event) {
        RTopic topic = redissonClient.getTopic(CHANNEL, StringCodec.INSTANCE);
        topic.publish(jsonSerializer.serialize(event));
    }
}
