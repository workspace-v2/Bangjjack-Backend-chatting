package com.project.bangjjack.domain.chat.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.bangjjack.domain.chat.application.usecase.ChatMessageUseCase;
import com.project.bangjjack.domain.chat.application.usecase.ChatRoomUseCase;
import com.project.bangjjack.global.infrastructure.websocket.WebSocketSessionStore;
import com.project.bangjjack.global.jwt.principal.MemberPrincipal;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class ChatWebSocketHandlerTest {

    private final WebSocketSessionStore store = new WebSocketSessionStore();
    private final ChatRoomUseCase rooms = mock(ChatRoomUseCase.class);
    private final ChatWebSocketHandler handler = new ChatWebSocketHandler(
            mock(ChatMessageUseCase.class), rooms, store, new ObjectMapper(), ObservationRegistry.NOOP);

    private WebSocketSession connect() {
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.getId()).willReturn("session-1");
        given(session.isOpen()).willReturn(true);
        MemberPrincipal principal = mock(MemberPrincipal.class);
        given(principal.getMemberId()).willReturn(1L);
        HashMap<String, Object> attributes = new HashMap<>();
        attributes.put("principal", principal);
        given(session.getAttributes()).willReturn(attributes);
        given(rooms.getMyRoomIds(1L)).willReturn(List.of(10L, 20L));
        handler.afterConnectionEstablished(session);
        return session;
    }

    @Test
    void connectionAndCloseManageOnlyLocalSessions() {
        WebSocketSession session = connect();
        assertThat(store.getAllSessions()).hasSize(1);
        assertThat(store.isSubscribed(10L, session)).isTrue();
        assertThat(store.isSubscribed(20L, session)).isTrue();

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(store.getAllSessions()).isEmpty();
        assertThat(store.getSubscribedRoomIds()).isEmpty();
    }

    @Test
    void failedCloseStillRemovesLocalSessionAndAllSubscriptions() throws Exception {
        WebSocketSession session = connect();
        doThrow(new IOException("connection lost")).when(session).close(CloseStatus.SESSION_NOT_RELIABLE);

        handler.closeUnresponsive(store.getById(session.getId()).orElseThrow());

        assertThat(store.getAllSessions()).isEmpty();
        assertThat(store.getSubscribedRoomIds()).isEmpty();
    }
}
