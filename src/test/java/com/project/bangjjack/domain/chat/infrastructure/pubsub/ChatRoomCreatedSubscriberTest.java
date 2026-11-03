package com.project.bangjjack.domain.chat.infrastructure.pubsub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.bangjjack.domain.chat.application.event.ChatRoomCreatedEvent;
import com.project.bangjjack.global.infrastructure.websocket.WebSocketSessionStore;
import com.project.bangjjack.global.jwt.principal.MemberPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("ChatRoomCreatedSubscriber - Redis 조회 없이 로컬 참여자 세션 구독")
class ChatRoomCreatedSubscriberTest {

    private static final Long ROOM_ID = 10L;
    private final RedissonClient redis = mock(RedissonClient.class);
    private final WebSocketSessionStore store = new WebSocketSessionStore();
    private final ChatRoomCreatedSubscriber subscriber =
            new ChatRoomCreatedSubscriber(redis, new ObjectMapper(), store);

    private WebSocketSession session(String id, Long userId, boolean open) {
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.getId()).willReturn(id);
        given(session.isOpen()).willReturn(open);
        if (userId == null) {
            given(session.getAttributes()).willReturn(Map.of());
        } else {
            MemberPrincipal principal = mock(MemberPrincipal.class);
            given(principal.getMemberId()).willReturn(userId);
            given(session.getAttributes()).willReturn(Map.of("principal", principal));
        }
        store.registerGlobal(session);
        return session;
    }

    @Test
    @DisplayName("여러 참여자의 모든 로컬 연결을 Redis 호출 없이 구독시킨다")
    void subscribesAllParticipantConnectionsWithoutRedis() {
        WebSocketSession firstDevice = session("s-1", 1L, true);
        WebSocketSession secondDevice = session("s-2", 1L, true);
        WebSocketSession otherParticipant = session("s-3", 2L, true);
        WebSocketSession nonParticipant = session("s-4", 3L, true);

        subscriber.subscribeActiveSessions(new ChatRoomCreatedEvent(ROOM_ID, List.of(1L, 2L, 4L)));

        assertThat(store.isSubscribed(ROOM_ID, firstDevice)).isTrue();
        assertThat(store.isSubscribed(ROOM_ID, secondDevice)).isTrue();
        assertThat(store.isSubscribed(ROOM_ID, otherParticipant)).isTrue();
        assertThat(store.isSubscribed(ROOM_ID, nonParticipant)).isFalse();
        assertThat(store.getSessionsByRoom(ROOM_ID)).hasSize(3);
        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("닫힌 연결과 인증 정보 없는 연결을 건너뛰고 정상 연결은 구독시킨다")
    void skipsClosedAndUnauthenticatedConnections() {
        WebSocketSession closed = session("closed", 1L, false);
        WebSocketSession unauthenticated = session("anonymous", null, true);
        WebSocketSession active = session("active", 2L, true);

        subscriber.subscribeActiveSessions(new ChatRoomCreatedEvent(ROOM_ID, List.of(1L, 2L)));

        assertThat(store.isSubscribed(ROOM_ID, closed)).isFalse();
        assertThat(store.isSubscribed(ROOM_ID, unauthenticated)).isFalse();
        assertThat(store.isSubscribed(ROOM_ID, active)).isTrue();
        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("같은 이벤트를 다시 받아도 세션을 중복 구독하지 않는다")
    void repeatedEventIsIdempotent() {
        session("s-1", 1L, true);
        ChatRoomCreatedEvent event = new ChatRoomCreatedEvent(ROOM_ID, List.of(1L));

        subscriber.subscribeActiveSessions(event);
        subscriber.subscribeActiveSessions(event);

        assertThat(store.getSessionsByRoom(ROOM_ID)).hasSize(1);
        verifyNoInteractions(redis);
    }
}
