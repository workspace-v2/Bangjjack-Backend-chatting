package com.project.bangjjack.domain.chat.infrastructure.pubsub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.bangjjack.domain.chat.application.event.ChatRoomCreatedEvent;
import com.project.bangjjack.global.infrastructure.websocket.WebSocketSessionStore;
import com.project.bangjjack.global.jwt.principal.MemberPrincipal;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashSet;
import java.util.Set;

@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class ChatRoomCreatedSubscriber {

    private static final String CHANNEL = "chat:system:room-created";

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
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
     * 이벤트의 참여자 정보와 로컬 연결만으로 구독한다. Redis 레지스트리 장애나 원격 세션 상태가
     * 로컬 구독에 영향을 주지 않도록 한다. 연결 종료와 서버 종료 시 세션은 로컬에서 정리된다.
     */
    void subscribeActiveSessions(ChatRoomCreatedEvent event) {
        Long roomId = event.roomId();
        Set<Long> participantIds = new HashSet<>(event.participantIds());
        for (WebSocketSession session : sessionStore.getAllSessions()) {
            if (!session.isOpen()
                    || !(session.getAttributes().get("principal") instanceof MemberPrincipal principal)
                    || !participantIds.contains(principal.getMemberId())) {
                continue;
            }
            sessionStore.subscribe(roomId, session);
            log.debug("[WS] 신규 채팅방 동적 구독 - userId={}, roomId={}, sessionId={}",
                    principal.getMemberId(), roomId, session.getId());
        }
    }
}
