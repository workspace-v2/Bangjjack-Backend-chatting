package com.project.bangjjack.domain.chat.presentation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.bangjjack.domain.chat.application.exception.ChatRoomErrorCode;
import com.project.bangjjack.domain.chat.application.dto.request.SendChatMessageRequest;
import com.project.bangjjack.domain.chat.application.dto.request.WebSocketInboundMessage;
import com.project.bangjjack.domain.chat.application.dto.response.WebSocketErrorResponse;
import com.project.bangjjack.domain.chat.application.exception.NotSubscribedException;
import com.project.bangjjack.domain.chat.application.usecase.ChatMessageUseCase;
import com.project.bangjjack.domain.chat.application.usecase.ChatRoomUseCase;
import com.project.bangjjack.global.common.exception.ApplicationException;
import com.project.bangjjack.global.infrastructure.websocket.WebSocketSessionStore;
import com.project.bangjjack.global.jwt.principal.MemberPrincipal;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final ChatMessageUseCase chatMessageUseCase;
    private final ChatRoomUseCase chatRoomUseCase;
    private final WebSocketSessionStore sessionStore;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        MemberPrincipal principal = getPrincipal(session);
        if (principal == null) {
            log.warn("[WS] 인증 정보 없는 연결 - sessionId={}", session.getId());
            closeSession(session);
            return;
        }

        Long userId = principal.getMemberId();
        WebSocketKeepAlive.markAlive(session);
        sessionStore.registerGlobal(session);
        try {
            List<Long> roomIds = chatRoomUseCase.getMyRoomIds(userId);
            roomIds.forEach(roomId -> sessionStore.subscribe(roomId, session));
            log.debug("[WS] 연결 확립 - userId={}, sessionId={}, 자동 구독 방 수={}", userId, session.getId(), roomIds.size());
        } catch (Exception e) {
            log.warn("[WS] 자동 구독 실패 - userId={}, sessionId={}, 원인={}", userId, session.getId(), e.getMessage());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        WebSocketInboundMessage inbound;
        try {
            inbound = objectMapper.readValue(message.getPayload(), WebSocketInboundMessage.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("[WS] 메시지 파싱 오류 - sessionId={}, 원인={}", session.getId(), e.getMessage());
            sendError(session, WebSocketErrorResponse.from(ChatRoomErrorCode.INVALID_WS_MESSAGE_FORMAT));
            return;
        }

        if (inbound.type() == null) {
            log.warn("[WS] 메시지 타입 누락 - sessionId={}", session.getId());
            sendError(session, WebSocketErrorResponse.from(ChatRoomErrorCode.MISSING_MESSAGE_TYPE));
            return;
        }

        MemberPrincipal principal = getPrincipal(session);

        try {
            inbound.validate();
            switch (inbound.type()) {
                case SUBSCRIBE -> {
                    log.debug("[WS] 구독 시도 - userId={}, roomId={}", principal.getMemberId(), inbound.roomId());
                    chatRoomUseCase.validateParticipant(inbound.roomId(), principal.getMemberId());
                    sessionStore.subscribe(inbound.roomId(), session);
                    log.debug("[WS] 구독 등록 완료 - userId={}, roomId={}", principal.getMemberId(), inbound.roomId());
                }
                case UNSUBSCRIBE -> {
                    sessionStore.unsubscribe(inbound.roomId(), session);
                    log.debug("[WS] 구독 해제 - userId={}, roomId={}", principal.getMemberId(), inbound.roomId());
                }
                case SEND -> observe("chat.message.send", principal.getMemberId(), inbound.roomId(), () -> {
                    log.debug("[WS] 메시지 송신 시도 - userId={}, roomId={}", principal.getMemberId(), inbound.roomId());
                    if (!sessionStore.isSubscribed(inbound.roomId(), session)) {
                        throw new NotSubscribedException();
                    }
                    chatMessageUseCase.sendMessage(principal.getMemberId(), inbound.roomId(), new SendChatMessageRequest(inbound.content()));
                });
                case READ -> observe("chat.message.read", principal.getMemberId(), inbound.roomId(), () -> {
                    log.debug("[WS] 읽음 처리 시도 - userId={}, roomId={}, messageId={}", principal.getMemberId(), inbound.roomId(), inbound.messageId());
                    if (!sessionStore.isSubscribed(inbound.roomId(), session)) {
                        throw new NotSubscribedException();
                    }
                    chatMessageUseCase.markAsRead(principal.getMemberId(), inbound.roomId(), inbound.messageId());
                });
                default -> log.warn("[WS] 알 수 없는 메시지 타입 - type={}", inbound.type());
            }
        } catch (ApplicationException e) {
            log.warn("[WS] 도메인 예외 - userId={}, code={}, 원인={}",
                    principal.getMemberId(), e.getErrorCode().getCode(), e.getMessage());
            sendError(session, WebSocketErrorResponse.from(e));
        } catch (Exception e) {
            log.error("[WS] 예상치 못한 오류 - userId={}, 원인={}",
                    principal.getMemberId(), e.getMessage(), e);
            sendError(session, WebSocketErrorResponse.from(ChatRoomErrorCode.WS_INTERNAL_ERROR));
        }
    }

    /**
     * WebSocket 메시지는 HTTP 요청이 아니라 자동으로 트레이스가 시작되지 않으므로, 처리 구간(방 락·저장·커밋)을 직접 감싼다.
     * 커밋 후 리스너도 이 구간 안에서 실행되어, Redis 발행까지 같은 트레이스로 이어진다.
     */
    private void observe(String name, Long userId, Long roomId, Runnable action) {
        Observation.createNotStarted(name, observationRegistry)
                .highCardinalityKeyValue("chat.room.id", String.valueOf(roomId))
                .highCardinalityKeyValue("user.id", String.valueOf(userId))
                .observe(action);
    }

    private void sendError(WebSocketSession session, WebSocketErrorResponse error) {
        if (!session.isOpen()) {
            return;
        }
        try {
            // 브로드캐스트·ping 과 같은 연결에 동시에 쓰지 않도록 저장소의 공용 전송 데코레이터로 보낸다
            sessionStore.getById(session.getId()).orElse(session)
                    .sendMessage(new TextMessage(objectMapper.writeValueAsString(error)));
        } catch (Exception ex) {
            log.warn("[WS] 에러 응답 전송 실패 - sessionId={}, 원인={}", session.getId(), ex.getMessage());
        }
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        WebSocketKeepAlive.markAlive(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cleanup(session);
        log.debug("[WS] 연결 해제 - sessionId={}, status={}", session.getId(), status);
    }

    /**
     * pong 응답이 없는 연결을 닫고 세션 정보를 지운다.
     * 끊긴 연결은 close 가 완료되지 않아 afterConnectionClosed 가 늦게 오거나 오지 않을 수 있어 정리를 직접 한다 (멱등).
     */
    void closeUnresponsive(WebSocketSession session) {
        try {
            session.close(CloseStatus.SESSION_NOT_RELIABLE);
        } catch (Exception e) {
            log.debug("[WS] 응답 없는 연결 종료 실패 - sessionId={}, 원인={}", session.getId(), e.getMessage());
        }
        cleanup(session);
    }

    void cleanup(WebSocketSession session) {
        sessionStore.removeAll(session);
        sessionStore.deregisterGlobal(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("[WS] 전송 오류 - sessionId={}, 원인={}", session.getId(), exception.getMessage());
    }

    private MemberPrincipal getPrincipal(WebSocketSession session) {
        return (MemberPrincipal) session.getAttributes().get("principal");
    }

    private void closeSession(WebSocketSession session) {
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (Exception e) {
            log.warn("[WS] 세션 강제 종료 실패 - sessionId={}", session.getId());
        }
    }
}
