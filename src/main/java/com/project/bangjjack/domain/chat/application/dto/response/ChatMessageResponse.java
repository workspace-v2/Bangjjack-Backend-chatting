package com.project.bangjjack.domain.chat.application.dto.response;

import com.project.bangjjack.domain.chat.domain.entity.Chat;
import com.project.bangjjack.domain.chat.domain.entity.MessageType;

import java.time.LocalDateTime;

public record ChatMessageResponse(
        Long messageId,
        Long seq,
        Long senderId,
        String content,
        MessageType messageType,
        Long applicationId,
        LocalDateTime createdAt
) {
    public static ChatMessageResponse from(Chat chat) {
        return new ChatMessageResponse(
                chat.getId(),
                chat.getSeq(),
                chat.getSenderId(),
                chat.getContent(),
                chat.getMessageType(),
                chat.getApplicationId(),
                chat.getCreatedAt()
        );
    }
}
