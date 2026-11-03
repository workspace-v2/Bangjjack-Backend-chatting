package com.project.bangjjack.domain.chat.presentation;

import com.project.bangjjack.global.infrastructure.websocket.WebSocketSessionStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * WebSocket ping/pong 으로 응답 없는 클라이언트 연결을 정리한다.
 * 네트워크가 끊긴 클라이언트(half-open 연결)는 종료 프레임을 보내지 못해 서버가 끊김을 알 수 없다.
 * 서버가 주기적으로 ping 을 보내고, pong 이 제한 시간 동안 없으면 연결을 닫고 세션 정보를 지운다.
 * (브라우저와 일반 WebSocket 클라이언트는 ping 에 자동으로 pong 을 보낸다)
 */
@Slf4j
@Component
public class WebSocketKeepAlive {

    static final String LAST_PONG_ATTR = "ws.lastPongAt";
    static final String PONG_TIMEOUT_METRIC = "chat.ws.pong.timeout.closed";

    private final WebSocketSessionStore sessionStore;
    private final ChatWebSocketHandler handler;
    private final long pongTimeoutMs;
    private final Counter timeoutClosed;

    public WebSocketKeepAlive(WebSocketSessionStore sessionStore, ChatWebSocketHandler handler, MeterRegistry meterRegistry,
                              @Value("${chat.ws.pong-timeout-ms:60000}") long pongTimeoutMs) {
        this.sessionStore = sessionStore;
        this.handler = handler;
        this.pongTimeoutMs = pongTimeoutMs;
        this.timeoutClosed = Counter.builder(PONG_TIMEOUT_METRIC)
                .description("pong 응답이 없어 서버가 닫은 WebSocket 연결 수")
                .register(meterRegistry);
    }

    static void markAlive(WebSocketSession session) {
        session.getAttributes().put(LAST_PONG_ATTR, System.currentTimeMillis());
    }

    @Scheduled(fixedDelayString = "${chat.ws.ping-interval-ms:20000}")
    public void pingAll() {
        long now = System.currentTimeMillis();
        for (WebSocketSession session : sessionStore.getAllSessions()) {
            if (!session.isOpen()) {
                continue;
            }
            Object last = session.getAttributes().get(LAST_PONG_ATTR);
            if (last instanceof Long lastPong && now - lastPong > pongTimeoutMs) {
                timeoutClosed.increment();
                log.info("[WS] pong 응답 없음 - 연결 정리. sessionId={}, 마지막 응답={}ms 전", session.getId(), now - lastPong);
                handler.closeUnresponsive(session);
                continue;
            }
            try {
                session.sendMessage(new PingMessage());
            } catch (Exception e) {
                log.debug("[WS] ping 전송 실패 - sessionId={}, 원인={}", session.getId(), e.getMessage());
            }
        }
    }
}
