package com.project.bangjjack.global.infrastructure.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@DisplayName("WebSocketSessionStore")
class WebSocketSessionStoreTest {

    @Test
    @DisplayName("한 세션은 여러 방에 구독돼도 같은 전송 데코레이터를 쓴다 (동시 전송 충돌 방지)")
    void sameDecoratorAcrossRooms() {
        WebSocketSessionStore store = new WebSocketSessionStore();
        WebSocketSession raw = mock(WebSocketSession.class);
        given(raw.getId()).willReturn("s-1");

        store.registerGlobal(raw);
        store.subscribe(1L, raw);
        store.subscribe(2L, raw);

        WebSocketSession inRoom1 = store.getSessionsByRoom(1L).iterator().next();
        WebSocketSession inRoom2 = store.getSessionsByRoom(2L).iterator().next();
        assertThat(inRoom1).isSameAs(inRoom2).isSameAs(store.getById("s-1").orElseThrow());
        assertThat(store.getAllSessions()).hasSize(1);
    }
}
