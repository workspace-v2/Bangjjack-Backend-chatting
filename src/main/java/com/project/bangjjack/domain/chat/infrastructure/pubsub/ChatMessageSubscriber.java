package com.project.bangjjack.domain.chat.infrastructure.pubsub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.bangjjack.domain.chat.application.dto.response.ChatMessageBroadcast;
import com.project.bangjjack.domain.chat.infrastructure.broadcaster.ChatBroadcaster;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RPatternTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.PatternStatusListener;
import org.redisson.client.codec.StringCodec;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 채팅 메시지 구독.
 *
 * Redis 연결이 끊겼다 복구되면 Redisson 이 패턴을 다시 구독한다. 끊긴 동안 발행된 메시지는 Pub/Sub 으로 다시 오지 않으므로,
 * 재구독(onPSubscribe)을 감지하면 이 서버에 연결된 세션에 재동기화 신호(SYNC_REQUIRED)를 보내 동기화 API 로 따라잡게 한다.
 * WebSocket 연결은 살아 있어 클라이언트 재연결이 일어나지 않는 경우를 위한 것이다.
 */
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
    private final MeterRegistry meterRegistry;
    // 기동 시 첫 구독은 재동기화 대상이 아니다. 등록을 마친 뒤의 구독 이벤트만 재구독으로 본다.
    private final AtomicBoolean ready = new AtomicBoolean(false);

    @PostConstruct
    public void subscribe() {
        try {
            RPatternTopic patternTopic = redissonClient.getPatternTopic(CHANNEL_PATTERN, StringCodec.INSTANCE);
            patternTopic.addListener(new PatternStatusListener() {
                @Override
                public void onPSubscribe(String pattern) {
                    if (!ready.get()) {
                        return;
                    }
                    meterRegistry.counter(PubSubMetrics.RESUBSCRIBED_METRIC).increment();
                    log.warn("[Redis Sub] 채팅 패턴 재구독 감지 - 연결된 세션에 재동기화 신호 전송. pattern={}", pattern);
                    chatBroadcaster.broadcastSyncRequired();
                }

                @Override
                public void onPUnsubscribe(String pattern) {
                    log.warn("[Redis Sub] 채팅 패턴 구독 해제 - pattern={}", pattern);
                }
            });
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
            ready.set(true);
            log.info("[Redis Sub] 채팅 구독 등록 완료 - pattern={}", CHANNEL_PATTERN);
        } catch (Exception e) {
            log.error("[Redis Sub] 구독 등록 실패 - 서버를 시작할 수 없습니다. 원인={}", e.getMessage());
            throw new IllegalStateException("Redis 채팅 구독 등록 실패", e);
        }
    }
}
