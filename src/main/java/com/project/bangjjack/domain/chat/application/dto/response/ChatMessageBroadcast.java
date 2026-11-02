package com.project.bangjjack.domain.chat.application.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.project.bangjjack.domain.chat.domain.entity.Chat;
import com.project.bangjjack.domain.chat.domain.entity.MessageType;

import java.time.LocalDateTime;

// Redis 메시지에 필드가 추가돼도(예: trace context) 이전 버전 서버가 역직렬화에 실패하지 않도록 한다.
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatMessageBroadcast(
        WebSocketBroadcastType type,
        Long messageId,
        Long roomId,
        Long senderId,
        String content,
        MessageType messageType,
        Long applicationId,
        LocalDateTime createdAt
) {
    public static ChatMessageBroadcast from(Chat chat, Long roomId) {
        return new ChatMessageBroadcast(
                WebSocketBroadcastType.CHAT_MESSAGE,
                chat.getId(),
                roomId,
                chat.getSenderId(),
                chat.getContent(),
                chat.getMessageType(),
                chat.getApplicationId(),
                chat.getCreatedAt()
        );
    }
}
