package com.project.bangjjack.domain.chat.presentation;

import com.project.bangjjack.global.infrastructure.websocket.WebSocketSessionStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@DisplayName("WebSocketKeepAlive")
class WebSocketKeepAliveTest {

    private final WebSocketSessionStore store = mock(WebSocketSessionStore.class);
    private final ChatWebSocketHandler handler = mock(ChatWebSocketHandler.class);
    private final WebSocketKeepAlive keepAlive = new WebSocketKeepAlive(store, handler, new SimpleMeterRegistry(), 60_000);

    private WebSocketSession session(long lastPongAgoMs) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(WebSocketKeepAlive.LAST_PONG_ATTR, System.currentTimeMillis() - lastPongAgoMs);
        given(session.getAttributes()).willReturn(attributes);
        given(session.isOpen()).willReturn(true);
        return session;
    }

    @Test
    @DisplayName("최근에 pong 이 온 연결에는 ping 을 보낸다")
    void aliveSession_getsPing() throws Exception {
        WebSocketSession alive = session(5_000);
        given(store.getAllSessions()).willReturn(List.of(alive));

        keepAlive.pingAll();

        then(alive).should().sendMessage(any(PingMessage.class));
        then(handler).should(never()).closeUnresponsive(any());
    }

    @Test
    @DisplayName("pong 이 제한 시간 넘게 없는 연결은 닫고 정리한다 (half-open 연결)")
    void unresponsiveSession_isClosed() throws Exception {
        WebSocketSession dead = session(61_000);
        given(store.getAllSessions()).willReturn(List.of(dead));

        keepAlive.pingAll();

        then(handler).should().closeUnresponsive(dead);
        then(dead).should(never()).sendMessage(any());
    }
}
