package com.project.bangjjack.domain.chat.application;

import com.project.bangjjack.domain.chat.application.event.ReadReceiptEvent;
import com.project.bangjjack.domain.chat.application.exception.NotChatParticipantException;
import com.project.bangjjack.domain.chat.application.usecase.ChatMessageUseCase;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoom;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoomParticipant;
import com.project.bangjjack.domain.chat.domain.service.ChatMessageGetService;
import com.project.bangjjack.domain.chat.domain.service.ChatRoomGetService;
import com.project.bangjjack.domain.chat.domain.service.ChatSaveService;
import com.project.bangjjack.domain.chat.domain.service.ChatStateUpdateService;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class ChatMessageUseCaseMarkAsReadTest {

    @Mock private ChatRoomGetService chatRoomGetService;
    @Mock private ChatSaveService chatSaveService;
    @Mock private ChatMessageGetService chatMessageGetService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private Validator validator;

    @Mock private ChatStateUpdateService chatStateUpdateService;

    @InjectMocks
    private ChatMessageUseCase chatMessageUseCase;

    private ChatRoomParticipant createParticipant(Long userId) {
        return ChatRoomParticipant.create(ChatRoom.createDirect(1L, "DM_1_2"), userId);
    }

    @Nested
    @DisplayName("WebSocket READ 처리 시")
    class MarkAsRead {

        @Test
        @DisplayName("신규 messageId이면 ReadReceiptEvent가 발행된다")
        void 신규_messageId이면_ReadReceiptEvent_발행() {
            // given
            Long userId = 1L;
            Long roomId = 10L;
            Long messageId = 100L;
            ChatRoomParticipant participant = createParticipant(userId);

            given(chatStateUpdateService.markAsRead(roomId, userId, messageId)).willReturn(true);

            // when
            chatMessageUseCase.markAsRead(userId, roomId, messageId);

            // then
            then(eventPublisher).should().publishEvent(new ReadReceiptEvent(roomId, userId, messageId));
        }

        @Test
        @DisplayName("이미 읽은 messageId이면 ReadReceiptEvent가 발행되지 않는다")
        void 이미_읽은_messageId이면_ReadReceiptEvent_미발행() {
            // given
            Long userId = 1L;
            Long roomId = 10L;
            Long messageId = 100L;
            ChatRoomParticipant participant = createParticipant(userId);
            participant.markAsRead(messageId); // 미리 읽음 처리

            given(chatStateUpdateService.markAsRead(roomId, userId, messageId)).willReturn(false);

            // when
            chatMessageUseCase.markAsRead(userId, roomId, messageId);

            // then
            then(eventPublisher).should(never()).publishEvent(any(ReadReceiptEvent.class));
        }

        @Test
        @DisplayName("비참여자가 읽음 처리하면 NotChatParticipantException이 발생한다")
        void 비참여자가_읽음_처리하면_예외가_발생한다() {
            // given
            Long outsiderId = 99L;
            Long roomId = 10L;

            doThrow(new NotChatParticipantException())
                    .when(chatStateUpdateService).markAsRead(roomId, outsiderId, 100L);

            // when & then
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> chatMessageUseCase.markAsRead(outsiderId, roomId, 100L)
            ).isInstanceOf(NotChatParticipantException.class);
        }
    }
}
