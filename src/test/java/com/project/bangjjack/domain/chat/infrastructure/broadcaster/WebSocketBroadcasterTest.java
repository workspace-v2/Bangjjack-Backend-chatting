package com.project.bangjjack.domain.chat.infrastructure.broadcaster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.bangjjack.global.infrastructure.JsonSerializer;
import com.project.bangjjack.global.infrastructure.websocket.WebSocketSessionStore;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("WebSocketBroadcaster")
class WebSocketBroadcasterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebSocketSessionStore sessionStore = mock(WebSocketSessionStore.class);
    private final WebSocketBroadcaster broadcaster =
            new WebSocketBroadcaster(sessionStore, new JsonSerializer(objectMapper), Tracer.NOOP);

    @Test
    @DisplayName("재동기화 신호는 이 서버에서 구독 중인 방마다, 그 방의 세션에 roomId 와 함께 보낸다")
    void broadcastSyncRequired_sendsToEachSubscribedRoom() throws Exception {
        WebSocketSession inRoom1 = openSession();
        WebSocketSession inRoom2 = openSession();
        given(sessionStore.getSubscribedRoomIds()).willReturn(Set.of(1L, 2L));
        given(sessionStore.getSessionsByRoom(1L)).willReturn(List.of(inRoom1));
        given(sessionStore.getSessionsByRoom(2L)).willReturn(List.of(inRoom2));

        broadcaster.broadcastSyncRequired();

        assertSyncRequired(inRoom1, 1L);
        assertSyncRequired(inRoom2, 2L);
    }

    private WebSocketSession openSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.isOpen()).willReturn(true);
        return session;
    }

    private void assertSyncRequired(WebSocketSession session, long roomId) throws Exception {
        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(sent.capture());
        JsonNode body = objectMapper.readTree(sent.getValue().getPayload());
        assertThat(body.get("type").asText()).isEqualTo("SYNC_REQUIRED");
        assertThat(body.get("roomId").asLong()).isEqualTo(roomId);
    }
}
