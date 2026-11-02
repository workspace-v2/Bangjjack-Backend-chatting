package com.project.bangjjack.domain.chat.infrastructure.broadcaster;

import com.project.bangjjack.domain.chat.application.dto.response.ChatMessageBroadcast;
import com.project.bangjjack.domain.chat.application.dto.response.ReadReceiptBroadcast;

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
}
