package com.project.bangjjack.domain.chat.domain.service;

import com.project.bangjjack.domain.chat.domain.entity.Chat;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoom;
import com.project.bangjjack.domain.chat.domain.entity.MessageType;
import com.project.bangjjack.domain.chat.domain.repository.ChatRepository;
import com.project.bangjjack.domain.chat.domain.repository.ChatRoomParticipantRepository;
import com.project.bangjjack.domain.chat.domain.repository.ChatRoomRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatSaveService")
class ChatSaveServiceTest {

    private static final Long ROOM_ID = 2L;
    private static final Long SENDER_ID = 3L;

    @Mock
    private ChatRepository chatRepository;

    @Mock
    private ChatRoomRepository chatRoomRepository;

    @Mock
    private ChatRoomParticipantRepository chatRoomParticipantRepository;

    @InjectMocks
    private ChatSaveService chatSaveService;

    @Test
    @DisplayName("메시지 저장은 채팅방 쓰기 락을 가장 먼저 잡고, 그 다음 INSERT, unread 갱신 순서로 실행한다 (데드락 방지 락 순서)")
    void save_locksRoomFirst() {
        ChatRoom chatRoom = mock(ChatRoom.class);
        given(chatRoom.getId()).willReturn(ROOM_ID);
        Chat saved = mock(Chat.class);
        LocalDateTime createdAt = LocalDateTime.of(2026, 11, 2, 17, 30);
        given(saved.getCreatedAt()).willReturn(createdAt);
        given(chatRepository.save(any(Chat.class))).willReturn(saved);

        Chat result = chatSaveService.save(SENDER_ID, chatRoom, "안녕하세요", MessageType.USER);

        assertThat(result).isSameAs(saved);
        InOrder order = inOrder(chatRoomRepository, chatRepository, chatRoomParticipantRepository, chatRoom);
        order.verify(chatRoomRepository).findByIdForUpdate(ROOM_ID);
        order.verify(chatRepository).save(any(Chat.class));
        order.verify(chatRoom).updateLastMessageAt(createdAt);
        order.verify(chatRoomParticipantRepository).incrementUnreadCountExcludingSender(ROOM_ID, SENDER_ID);
    }
}
