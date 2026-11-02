package com.project.bangjjack.domain.chat.infrastructure.pubsub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.bangjjack.domain.chat.application.dto.response.ReadReceiptBroadcast;
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
public class ReadReceiptSubscriber {

    private static final String CHANNEL_PATTERN = "chat:read-receipt:*";

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final ChatBroadcaster chatBroadcaster;
    private final PubSubTracing pubSubTracing;

    @PostConstruct
    public void subscribe() {
        try {
            RPatternTopic patternTopic = redissonClient.getPatternTopic(CHANNEL_PATTERN, StringCodec.INSTANCE);
            patternTopic.addListener(String.class, (pattern, channel, json) -> {
                log.debug("[Redis Sub] 읽음 알림 수신 - channel={}", channel);
                try {
                    pubSubTracing.receive("chat.pubsub.receive-read-receipt", channel, json, node -> {
                        ReadReceiptBroadcast broadcast = objectMapper.treeToValue(node, ReadReceiptBroadcast.class);
                        pubSubTracing.tag("chat.room.id", broadcast.roomId());
                        pubSubTracing.tag("chat.read.message.id", broadcast.lastReadMessageId());
                        chatBroadcaster.broadcastReadReceipt(broadcast.roomId(), broadcast);
                        log.debug("[Redis Sub] 읽음 알림 브로드캐스트 완료 - roomId={}, readerId={}", broadcast.roomId(), broadcast.readerId());
                    });
                } catch (Exception e) {
                    log.error("[Redis Sub] 읽음 알림 처리 실패 - channel={}, 원인={}", channel, e.getMessage(), e);
                }
            });
            log.info("[Redis Sub] 읽음 알림 구독 등록 완료 - pattern={}", CHANNEL_PATTERN);
        } catch (Exception e) {
            log.error("[Redis Sub] 읽음 알림 구독 등록 실패 - 원인={}", e.getMessage());
            throw new IllegalStateException("Redis 읽음 알림 구독 등록 실패", e);
        }
    }
}
