package com.project.bangjjack.domain.chat.application;

import com.project.bangjjack.domain.chat.application.dto.request.SendChatMessageRequest;
import com.project.bangjjack.domain.chat.application.usecase.ChatMessageUseCase;
import com.project.bangjjack.domain.chat.domain.entity.Chat;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoom;
import com.project.bangjjack.domain.chat.domain.entity.MessageType;
import com.project.bangjjack.domain.chat.domain.service.ChatMessageGetService;
import com.project.bangjjack.domain.chat.domain.service.ChatRoomGetService;
import com.project.bangjjack.domain.chat.domain.service.ChatSaveService;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatMessageUseCase 메시지 전송")
class ChatMessageUseCaseSendMessageTest {

    private static final Long SENDER_ID = 3L;
    private static final Long ROOM_ID = 2L;

    @Mock private ChatRoomGetService chatRoomGetService;
    @Mock private ChatSaveService chatSaveService;
    @Mock private ChatMessageGetService chatMessageGetService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private Validator validator;

    @InjectMocks
    private ChatMessageUseCase chatMessageUseCase;

    @Test
    @DisplayName("채팅방 락을 먼저 잡은 뒤 재입장 대상을 조회하고, 그 다음 메시지를 저장한다")
    void sendMessage_locksRoomBeforeReadingLeftParticipants() {
        ChatRoom chatRoom = mock(ChatRoom.class);
        given(chatRoomGetService.getByIdAndValidateParticipant(ROOM_ID, SENDER_ID)).willReturn(chatRoom);
        given(chatRoomGetService.findLeftParticipantsByRoomId(ROOM_ID, SENDER_ID)).willReturn(List.of());
        given(chatSaveService.save(eq(SENDER_ID), eq(chatRoom), eq("안녕하세요"), eq(MessageType.USER)))
                .willReturn(mock(Chat.class));

        chatMessageUseCase.sendMessage(SENDER_ID, ROOM_ID, new SendChatMessageRequest("안녕하세요"));

        InOrder order = inOrder(chatSaveService, chatRoomGetService);
        order.verify(chatSaveService).lockRoom(ROOM_ID);
        order.verify(chatRoomGetService).findLeftParticipantsByRoomId(ROOM_ID, SENDER_ID);
        order.verify(chatSaveService).save(SENDER_ID, chatRoom, "안녕하세요", MessageType.USER);
    }
}
