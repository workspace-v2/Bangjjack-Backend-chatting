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
import static org.mockito.Mockito.verify;

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

    @Mock private ChatStateUpdateService chatStateUpdateService;

    @InjectMocks
    private ChatSaveService chatSaveService;

    @Test
    @DisplayName("메시지 저장은 채팅방 쓰기 락을 가장 먼저 잡고, 그 다음 seq 발급, INSERT, unread 갱신 순서로 실행한다 (데드락 방지 락 순서)")
    void save_locksRoomFirst() {
        ChatRoom chatRoom = mock(ChatRoom.class);
        given(chatRoom.getId()).willReturn(ROOM_ID);
        Chat saved = mock(Chat.class);
        LocalDateTime createdAt = LocalDateTime.of(2026, 11, 2, 17, 30);
        given(saved.getId()).willReturn(100L);
        given(saved.getCreatedAt()).willReturn(createdAt);
        given(chatRepository.save(any(Chat.class))).willReturn(saved);

        Chat result = chatSaveService.save(SENDER_ID, chatRoom, "안녕하세요", MessageType.USER);

        assertThat(result).isSameAs(saved);
        InOrder order = inOrder(chatStateUpdateService, chatRoomRepository, chatRepository, chatRoomParticipantRepository);
        order.verify(chatStateUpdateService).lockRoom(ROOM_ID);
        order.verify(chatStateUpdateService).nextSeq(ROOM_ID);
        order.verify(chatRepository).save(any(Chat.class));
        order.verify(chatRoomRepository).updateLastMessage(ROOM_ID, 100L, createdAt);
        order.verify(chatRoomParticipantRepository).incrementUnreadCountExcludingSender(ROOM_ID, SENDER_ID);
    }

    @Test
    @DisplayName("사용자 메시지는 재입장 작업 이후 메시지를 저장한다")
    void saveWithRejoin_preparesParticipantsBeforeSaving() {
        ChatRoom room = mock(ChatRoom.class);
        given(room.getId()).willReturn(ROOM_ID);
        Chat saved = mock(Chat.class);
        given(saved.getId()).willReturn(200L);
        given(chatRepository.save(any(Chat.class))).willReturn(saved);

        assertThat(chatSaveService.saveWithRejoin(SENDER_ID, room, "안녕하세요")).isSameAs(saved);

        InOrder order = inOrder(chatStateUpdateService, chatRepository, chatRoomRepository, chatRoomParticipantRepository);
        order.verify(chatStateUpdateService).prepareUserMessage(ROOM_ID, SENDER_ID);
        order.verify(chatStateUpdateService).nextSeq(ROOM_ID);
        order.verify(chatRepository).save(any(Chat.class));
        order.verify(chatRoomRepository).updateLastMessage(ROOM_ID, 200L, null);
        order.verify(chatRoomParticipantRepository).incrementUnreadCountExcludingSender(ROOM_ID, SENDER_ID);
    }

    @Test
    @DisplayName("방 락 안에서 발급한 seq 를 메시지에 저장한다")
    void save_assignsSeqFromRoom() {
        ChatRoom room = mock(ChatRoom.class);
        given(room.getId()).willReturn(ROOM_ID);
        given(chatStateUpdateService.nextSeq(ROOM_ID)).willReturn(7L);
        given(chatRepository.save(any(Chat.class))).willAnswer(invocation -> invocation.getArgument(0));

        Chat saved = chatSaveService.save(SENDER_ID, room, "안녕하세요", MessageType.USER);

        assertThat(saved.getSeq()).isEqualTo(7L);
    }
}
