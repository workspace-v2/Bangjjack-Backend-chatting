package com.project.bangjjack.domain.chat.infrastructure.pubsub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.bangjjack.domain.chat.application.dto.response.ChatMessageBroadcast;
import com.project.bangjjack.domain.chat.infrastructure.broadcaster.ChatBroadcaster;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RPatternTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class ChatMessageSubscriber {

    private static final String CHANNEL_PATTERN = "chat:room:*";

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final ChatBroadcaster chatBroadcaster;
    private final PubSubTracing pubSubTracing;

    @PostConstruct
    public void subscribe() {
        try {
            RPatternTopic patternTopic = redissonClient.getPatternTopic(CHANNEL_PATTERN, StringCodec.INSTANCE);
            patternTopic.addListener(String.class, (pattern, channel, json) -> {
                log.debug("[Redis Sub] 메시지 수신 - channel={}", channel);
                try {
                    pubSubTracing.receive("chat.pubsub.receive", channel, json, node -> {
                        ChatMessageBroadcast broadcast = objectMapper.treeToValue(node, ChatMessageBroadcast.class);
                        pubSubTracing.tag("chat.room.id", broadcast.roomId());
                        pubSubTracing.tag("chat.message.id", broadcast.messageId());
                        chatBroadcaster.broadcastToRoom(broadcast.roomId(), broadcast);
                        log.debug("[Redis Sub] 브로드캐스트 완료 - roomId={}, messageId={}", broadcast.roomId(), broadcast.messageId());
                    });
                } catch (Exception e) {
                    log.error("[Redis Sub] 메시지 처리 실패 - channel={}, 원인={}", channel, e.getMessage(), e);
                }
            });
            log.info("[Redis Sub] 채팅 구독 등록 완료 - pattern={}", CHANNEL_PATTERN);
        } catch (Exception e) {
            log.error("[Redis Sub] 구독 등록 실패 - 서버를 시작할 수 없습니다. 원인={}", e.getMessage());
            throw new IllegalStateException("Redis 채팅 구독 등록 실패", e);
        }
    }
}
