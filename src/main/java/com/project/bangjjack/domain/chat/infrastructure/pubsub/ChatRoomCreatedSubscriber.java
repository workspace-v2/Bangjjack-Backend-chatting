package com.project.bangjjack.domain.chat.infrastructure.pubsub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.bangjjack.domain.chat.application.event.ChatRoomCreatedEvent;
import com.project.bangjjack.domain.chat.domain.service.ChatRoomGetService;
import com.project.bangjjack.domain.chat.infrastructure.broadcaster.ChatBroadcaster;
import com.project.bangjjack.global.infrastructure.websocket.WebSocketSessionStore;
import com.project.bangjjack.global.jwt.principal.MemberPrincipal;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.StatusListener;
import org.redisson.client.codec.StringCodec;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class ChatRoomCreatedSubscriber {

    private static final String CHANNEL = "chat:system:room-created";

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final WebSocketSessionStore sessionStore;
    private final RoomSubscriptionRecovery roomSubscriptionRecovery;
    private final ChatBroadcaster chatBroadcaster;
    private final ChatRoomGetService chatRoomGetService;
    // 기동 시 첫 구독은 복구 대상이 아니다. 등록을 마친 뒤의 구독 이벤트만 재구독으로 본다.
    private final AtomicBoolean ready = new AtomicBoolean(false);

    @PostConstruct
    public void subscribe() {
        RTopic topic = redissonClient.getTopic(CHANNEL, StringCodec.INSTANCE);
        // 끊긴 동안 발행된 방 생성 이벤트는 다시 오지 않으므로, 재구독되면 DB 기준으로 빠진 방 구독을 채운다
        topic.addListener(new StatusListener() {
            @Override
            public void onSubscribe(String channel) {
                if (!ready.get()) {
                    return;
                }
                log.warn("[Redis Sub] 채팅방 생성 채널 재구독 감지 - 로컬 세션의 방 구독 복구 요청. channel={}", channel);
                roomSubscriptionRecovery.requestRecovery();
            }

            @Override
            public void onUnsubscribe(String channel) {
                log.warn("[Redis Sub] 채팅방 생성 채널 구독 해제 - channel={}", channel);
            }
        });
        topic.addListener(String.class, (channel, json) -> {
            try {
                ChatRoomCreatedEvent event = objectMapper.readValue(json, ChatRoomCreatedEvent.class);
                subscribeActiveSessions(event);
            } catch (Exception e) {
                log.error("[Redis Sub] 채팅방 생성 이벤트 처리 실패 - 원인={}", e.getMessage(), e);
            }
        });
        ready.set(true);
        log.info("[Redis Sub] 채팅방 생성 구독 등록 완료 - channel={}", CHANNEL);
    }

    /**
     * 이벤트의 참여자 목록은 생성 시점의 스냅샷이므로 DB의 현재 ACTIVE 참여자와 교차 확인한다.
     * 이미 나간 참여자나 삭제된 방은 지연 이벤트로 구독시키지 않는다.
     * 재시도로 늦게 온 이벤트(delayed)면 새로 구독한 세션에 재동기화 신호를 보낸다 (그 사이 메시지를 놓쳤을 수 있음).
     */
    void subscribeActiveSessions(ChatRoomCreatedEvent event) {
        Long roomId = event.roomId();
        Set<Long> participantIds = new HashSet<>(event.participantIds());
        List<WebSocketSession> sessions = sessionStore.getAllSessions();
        boolean hasLocalParticipant = sessions.stream().anyMatch(session -> session.isOpen()
                && session.getAttributes().get("principal") instanceof MemberPrincipal principal
                && participantIds.contains(principal.getMemberId()));
        if (!hasLocalParticipant) {
            return;
        }
        try {
            participantIds.retainAll(chatRoomGetService.findActiveUserIdsByRoomId(roomId));
        } catch (RuntimeException e) {
            log.warn("[Redis Sub] 현재 참여자 조회 실패, DB 기반 구독 복구 요청 - roomId={}", roomId, e);
            roomSubscriptionRecovery.requestRecovery();
            return;
        }
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()
                    || !(session.getAttributes().get("principal") instanceof MemberPrincipal principal)
                    || !participantIds.contains(principal.getMemberId())) {
                continue;
            }
            if (!sessionStore.subscribeIfMissing(roomId, session)) {
                continue;
            }
            if (event.delayed()) {
                chatBroadcaster.sendSyncRequired(session, roomId);
            }
            log.debug("[WS] 신규 채팅방 동적 구독 - userId={}, roomId={}, sessionId={}",
                    principal.getMemberId(), roomId, session.getId());
        }
    }
}
