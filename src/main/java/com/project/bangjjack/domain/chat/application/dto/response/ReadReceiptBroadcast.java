package com.project.bangjjack.domain.chat.application.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;

// Redis 메시지에 필드가 추가돼도(예: trace context) 이전 버전 서버가 역직렬화에 실패하지 않도록 한다.
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReadReceiptBroadcast(
        WebSocketBroadcastType type,
        Long roomId,
        Long readerId,
        Long lastReadMessageId,
        LocalDateTime readAt
) {
    public static ReadReceiptBroadcast of(Long roomId, Long readerId, Long lastReadMessageId) {
        return new ReadReceiptBroadcast(WebSocketBroadcastType.READ_RECEIPT, roomId, readerId, lastReadMessageId, LocalDateTime.now());
    }
}
