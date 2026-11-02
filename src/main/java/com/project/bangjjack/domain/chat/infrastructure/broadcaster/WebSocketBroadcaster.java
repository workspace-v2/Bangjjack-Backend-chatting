package com.project.bangjjack.domain.chat.infrastructure.broadcaster;

import com.project.bangjjack.domain.chat.application.dto.response.ChatMessageBroadcast;
import com.project.bangjjack.domain.chat.application.dto.response.ReadReceiptBroadcast;
import com.project.bangjjack.global.infrastructure.JsonSerializer;
import com.project.bangjjack.global.infrastructure.websocket.WebSocketSessionStore;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collection;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketBroadcaster implements ChatBroadcaster {

    private final WebSocketSessionStore sessionStore;
    private final JsonSerializer jsonSerializer;
    private final Tracer tracer;

    @Override
    public void broadcastToRoom(Long roomId, ChatMessageBroadcast broadcast) {
        sendToRoom(roomId, jsonSerializer.serialize(broadcast), broadcast.type().name());
        log.debug("[WS Broadcast] 채팅 메시지 브로드캐스트 완료 - roomId={}", roomId);
    }

    @Override
    public void broadcastReadReceipt(Long roomId, ReadReceiptBroadcast broadcast) {
        sendToRoom(roomId, jsonSerializer.serialize(broadcast), broadcast.type().name());
        log.debug("[WS Broadcast] 읽음 알림 브로드캐스트 완료 - roomId={}, readerId={}", roomId, broadcast.readerId());
    }

    /**
     * 방에 연결된 세션에 전송한다. 전송 대상·성공·실패 세션 수를 span 에 남겨, 수신 서버에서 메시지가 어디까지 전달됐는지 확인한다.
     */
    private void sendToRoom(Long roomId, String json, String type) {
        Span span = tracer.nextSpan()
                .name("chat.ws.broadcast")
                .tag("broadcast.type", type)
                .tag("chat.room.id", String.valueOf(roomId))
                .start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            Collection<WebSocketSession> sessions = sessionStore.getSessionsByRoom(roomId);
            int sent = 0;
            int failed = 0;
            for (WebSocketSession session : sessions) {
                if (!session.isOpen()) {
                    continue;
                }
                try {
                    session.sendMessage(new TextMessage(json));
                    sent++;
                } catch (Exception e) {
                    failed++;
                    span.error(e); // 나머지 세션 전송은 계속하되, 실패를 트레이스 오류로 남긴다
                    log.warn("[WS Broadcast] 전송 실패 - sessionId={}, roomId={}, 원인={}", session.getId(), roomId, e.getMessage());
                }
            }
            span.tag("ws.sessions", String.valueOf(sessions.size()));
            span.tag("ws.sent", String.valueOf(sent));
            span.tag("ws.failed", String.valueOf(failed));
        } finally {
            span.end();
        }
    }
}
