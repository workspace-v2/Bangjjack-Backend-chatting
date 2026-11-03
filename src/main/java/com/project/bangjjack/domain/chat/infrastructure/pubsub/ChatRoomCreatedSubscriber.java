package com.project.bangjjack.domain.chat.infrastructure.pubsub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.bangjjack.domain.chat.application.event.ChatRoomCreatedEvent;
import com.project.bangjjack.global.infrastructure.redis.WebSocketSessionRegistry;
import com.project.bangjjack.global.infrastructure.websocket.WebSocketSessionStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class ChatRoomCreatedSubscriber {

    private static final String CHANNEL = "chat:system:room-created";

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final WebSocketSessionRegistry sessionRegistry;
    private final WebSocketSessionStore sessionStore;

    @PostConstruct
    public void subscribe() {
        RTopic topic = redissonClient.getTopic(CHANNEL, StringCodec.INSTANCE);
        topic.addListener(String.class, (channel, json) -> {
            try {
                ChatRoomCreatedEvent event = objectMapper.readValue(json, ChatRoomCreatedEvent.class);
                subscribeActiveSessions(event);
            } catch (Exception e) {
                log.error("[Redis Sub] 채팅방 생성 이벤트 처리 실패 - 원인={}", e.getMessage(), e);
            }
        });
        log.info("[Redis Sub] 채팅방 생성 구독 등록 완료 - channel={}", CHANNEL);
    }

    /**
     * 새 방 참여자의 세션 중 이 서버에 연결된 것만 구독시킨다. 다른 서버의 세션은 그 서버가 같은 이벤트를 받아 처리한다.
     * 로컬에 없는 세션을 "죽은 세션"으로 지우면 다른 서버의 정상 세션까지 지우게 된다 (다중 서버).
     * 소유 서버의 heartbeat 가 만료된 세션만 정리한다.
     */
    void subscribeActiveSessions(ChatRoomCreatedEvent event) {
        Long roomId = event.roomId();
        for (Long userId : event.participantIds()) {
            Set<String> sessionIds = sessionRegistry.getSessionIds(userId);
            for (String sessionId : sessionIds) {
                Optional<WebSocketSession> local = sessionStore.getById(sessionId);
                if (local.isPresent()) {
                    sessionStore.subscribe(roomId, local.get());
                    log.debug("[WS] 신규 채팅방 동적 구독 - userId={}, roomId={}, sessionId={}", userId, roomId, sessionId);
                    continue;
                }
                String owner = sessionRegistry.ownerOf(sessionId);
                if (owner != null && !sessionRegistry.isLocalServer(owner) && !sessionRegistry.isServerAlive(owner)) {
                    sessionRegistry.remove(userId, sessionId);
                    log.debug("[WS] 죽은 서버의 세션 제거 - userId={}, sessionId={}, owner={}", userId, sessionId, owner);
                }
            }
        }
    }
}
