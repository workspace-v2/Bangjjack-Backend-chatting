package com.project.bangjjack.domain.chat.infrastructure.broadcaster;

import com.project.bangjjack.domain.chat.application.dto.response.ChatMessageBroadcast;
import com.project.bangjjack.domain.chat.application.dto.response.ReadReceiptBroadcast;
import org.springframework.web.socket.WebSocketSession;

/**
 * 채팅 브로드캐스트 추상화.
 * - 현재: WebSocketBroadcaster (순수 WebSocket)
 * - 향후 STOMP 전환 시: StompBroadcaster 구현체 추가 후 @Primary 교체
 */
public interface ChatBroadcaster {
    void broadcastToRoom(Long roomId, ChatMessageBroadcast broadcast);
    void broadcastReadReceipt(Long roomId, ReadReceiptBroadcast broadcast);

    /** 이 서버에 연결된 모든 방의 세션에 재동기화 신호를 보낸다. */
    void broadcastSyncRequired();

    /**
     * 한 세션에 특정 방의 재동기화 신호를 보낸다.
     * 전송에 실패하면 그 연결은 더 믿을 수 없으므로 닫는다. 클라이언트는 재연결하면서 DB 기준으로 다시 구독하고 동기화한다.
     *
     * @return 전송했으면 true, 실패해 연결을 닫았으면 false
     */
    boolean sendSyncRequired(WebSocketSession session, Long roomId);
}
