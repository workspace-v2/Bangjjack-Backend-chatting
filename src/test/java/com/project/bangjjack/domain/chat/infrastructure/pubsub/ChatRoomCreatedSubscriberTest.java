package com.project.bangjjack.domain.chat.infrastructure.pubsub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.bangjjack.domain.chat.application.event.ChatRoomCreatedEvent;
import com.project.bangjjack.global.infrastructure.redis.WebSocketSessionRegistry;
import com.project.bangjjack.global.infrastructure.websocket.WebSocketSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@DisplayName("ChatRoomCreatedSubscriber - 새 방 참여자 세션 구독")
class ChatRoomCreatedSubscriberTest {

    private static final Long ROOM_ID = 10L;
    private static final Long USER_ID = 1L;
    private static final String SESSION_ID = "s-1";

    private WebSocketSessionRegistry registry;
    private WebSocketSessionStore store;
    private ChatRoomCreatedSubscriber subscriber;

    @BeforeEach
    void setUp() {
        registry = mock(WebSocketSessionRegistry.class);
        store = mock(WebSocketSessionStore.class);
        subscriber = new ChatRoomCreatedSubscriber(mock(RedissonClient.class), new ObjectMapper(), registry, store);
        given(registry.getSessionIds(USER_ID)).willReturn(Set.of(SESSION_ID));
    }

    private void handle() {
        subscriber.subscribeActiveSessions(new ChatRoomCreatedEvent(ROOM_ID, List.of(USER_ID)));
    }

    @Test
    @DisplayName("이 서버에 연결된 세션이면 새 방에 구독시킨다")
    void localSession_subscribes() {
        WebSocketSession session = mock(WebSocketSession.class);
        given(store.getById(SESSION_ID)).willReturn(Optional.of(session));

        handle();

        then(store).should().subscribe(ROOM_ID, session);
        then(registry).should(never()).remove(anyLong(), anyString());
    }

    @Test
    @DisplayName("다른 서버의 세션이고 그 서버가 살아 있으면 지우지 않는다 (다중 서버 오삭제 방지)")
    void remoteSessionOfAliveServer_isKept() {
        given(store.getById(SESSION_ID)).willReturn(Optional.empty());
        given(registry.ownerOf(SESSION_ID)).willReturn("server-b");
        given(registry.isLocalServer("server-b")).willReturn(false);
        given(registry.isServerAlive("server-b")).willReturn(true);

        handle();

        then(registry).should(never()).remove(anyLong(), anyString());
        then(store).should(never()).subscribe(any(), any());
    }

    @Test
    @DisplayName("소유 서버의 heartbeat 가 만료된 세션(강제 종료된 서버)은 지운다")
    void sessionOfDeadServer_isRemoved() {
        given(store.getById(SESSION_ID)).willReturn(Optional.empty());
        given(registry.ownerOf(SESSION_ID)).willReturn("server-dead");
        given(registry.isLocalServer("server-dead")).willReturn(false);
        given(registry.isServerAlive("server-dead")).willReturn(false);

        handle();

        then(registry).should().remove(USER_ID, SESSION_ID);
    }

    @Test
    @DisplayName("소유 서버 정보가 없는 세션(구버전 서버가 등록)은 판단할 수 없으므로 지우지 않는다")
    void sessionWithoutOwner_isKept() {
        given(store.getById(SESSION_ID)).willReturn(Optional.empty());
        given(registry.ownerOf(SESSION_ID)).willReturn(null);

        handle();

        then(registry).should(never()).remove(anyLong(), anyString());
    }
}
