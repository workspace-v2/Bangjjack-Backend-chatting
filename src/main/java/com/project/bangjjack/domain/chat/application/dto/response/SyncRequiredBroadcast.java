package com.project.bangjjack.domain.chat.application.dto.response;

/**
 * 실시간 수신이 끊겼을 수 있으니 이 방을 동기화 API 로 따라잡으라는 신호.
 * Redis 구독이 복구됐을 때(끊긴 동안 발행된 메시지는 Pub/Sub 으로 다시 오지 않음) 이 서버에 연결된 세션에 보낸다.
 */
public record SyncRequiredBroadcast(
        WebSocketBroadcastType type,
        Long roomId
) {
    public static SyncRequiredBroadcast of(Long roomId) {
        return new SyncRequiredBroadcast(WebSocketBroadcastType.SYNC_REQUIRED, roomId);
    }
}
