package com.project.bangjjack.domain.chat.application;

import com.project.bangjjack.domain.chat.application.dto.response.ChatMessageSyncResponse;
import com.project.bangjjack.domain.chat.application.exception.AlreadyLeftChatRoomException;
import com.project.bangjjack.domain.chat.application.usecase.ChatMessageUseCase;
import com.project.bangjjack.domain.chat.domain.entity.Chat;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoom;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoomParticipant;
import com.project.bangjjack.domain.chat.domain.entity.MessageType;
import com.project.bangjjack.domain.chat.domain.service.ChatMessageGetService;
import com.project.bangjjack.domain.chat.domain.service.ChatRoomGetService;
import com.project.bangjjack.domain.chat.domain.service.ChatSaveService;
import com.project.bangjjack.domain.chat.domain.service.ChatStateUpdateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatMessageUseCase - 동기화")
class ChatMessageUseCaseSyncTest {

    private static final Long ROOM_ID = 10L;
    private static final Long USER_ID = 2L;

    @Mock private ChatRoomGetService chatRoomGetService;
    @Mock private ChatSaveService chatSaveService;
    @Mock private ChatMessageGetService chatMessageGetService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ChatStateUpdateService chatStateUpdateService;

    @InjectMocks
    private ChatMessageUseCase chatMessageUseCase;

    private final ChatRoom room = ChatRoom.createDirect(1L, "DM_1_2");

    private Chat chat(long seq) {
        Chat chat = Chat.create(1L, room, "메시지 " + seq, MessageType.USER, seq);
        ReflectionTestUtils.setField(chat, "id", 100L + seq);
        return chat;
    }

    private List<Chat> chats(long fromSeq, long toSeq) {
        return LongStream.rangeClosed(fromSeq, toSeq).mapToObj(this::chat).toList();
    }

    @Test
    @DisplayName("afterSeq 이후 메시지를 seq 순서로 반환하고, 끝까지 받았으면 방의 마지막 seq 를 syncedSeq 로 준다")
    void sync_returnsMissingMessages() {
        given(chatRoomGetService.getActiveParticipant(ROOM_ID, USER_ID)).willReturn(ChatRoomParticipant.create(room, USER_ID));
        given(chatRoomGetService.getLastSeq(ROOM_ID)).willReturn(5L);
        given(chatMessageGetService.getMessagesForSync(ROOM_ID, 2L, 5L, null, 100)).willReturn(chats(3, 5));

        ChatMessageSyncResponse response = chatMessageUseCase.syncMessages(USER_ID, ROOM_ID, 2L, 100);

        assertThat(response.messages()).extracting(m -> m.seq()).containsExactly(3L, 4L, 5L);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.syncedSeq()).isEqualTo(5L);
    }

    @Test
    @DisplayName("size 보다 많으면 hasNext 와 함께 이번 페이지의 마지막 seq 를 syncedSeq 로 준다")
    void sync_paginates() {
        given(chatRoomGetService.getActiveParticipant(ROOM_ID, USER_ID)).willReturn(ChatRoomParticipant.create(room, USER_ID));
        given(chatRoomGetService.getLastSeq(ROOM_ID)).willReturn(10L);
        given(chatMessageGetService.getMessagesForSync(ROOM_ID, 0L, 10L, null, 3)).willReturn(chats(1, 4));

        ChatMessageSyncResponse response = chatMessageUseCase.syncMessages(USER_ID, ROOM_ID, 0L, 3);

        assertThat(response.messages()).hasSize(3);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.syncedSeq()).isEqualTo(3L);
    }

    @Test
    @DisplayName("볼 수 없는 메시지만 남아 비어 있어도 syncedSeq 는 방의 마지막 seq 로 넘어가 같은 gap 을 반복하지 않는다")
    void sync_emptyResult_advancesToLatest() {
        given(chatRoomGetService.getActiveParticipant(ROOM_ID, USER_ID)).willReturn(ChatRoomParticipant.create(room, USER_ID));
        given(chatRoomGetService.getLastSeq(ROOM_ID)).willReturn(8L);
        given(chatMessageGetService.getMessagesForSync(ROOM_ID, 5L, 8L, null, 100)).willReturn(List.of());

        ChatMessageSyncResponse response = chatMessageUseCase.syncMessages(USER_ID, ROOM_ID, 5L, 100);

        assertThat(response.messages()).isEmpty();
        assertThat(response.syncedSeq()).isEqualTo(8L);
    }

    @Test
    @DisplayName("동기화는 읽음 처리를 하지 않는다")
    void sync_doesNotMarkAsRead() {
        given(chatRoomGetService.getActiveParticipant(ROOM_ID, USER_ID)).willReturn(ChatRoomParticipant.create(room, USER_ID));
        given(chatRoomGetService.getLastSeq(ROOM_ID)).willReturn(1L);
        given(chatMessageGetService.getMessagesForSync(ROOM_ID, 0L, 1L, null, 100)).willReturn(chats(1, 1));

        chatMessageUseCase.syncMessages(USER_ID, ROOM_ID, 0L, 100);

        verifyNoInteractions(chatStateUpdateService, eventPublisher);
    }

    @Test
    @DisplayName("나간 참여자는 동기화할 수 없다")
    void sync_leftParticipant_throws() {
        given(chatRoomGetService.getActiveParticipant(ROOM_ID, USER_ID)).willThrow(new AlreadyLeftChatRoomException());

        assertThatThrownBy(() -> chatMessageUseCase.syncMessages(USER_ID, ROOM_ID, 0L, 100))
                .isInstanceOf(AlreadyLeftChatRoomException.class);
        then(chatMessageGetService).should(never()).getMessagesForSync(anyLong(), anyLong(), anyLong(), org.mockito.ArgumentMatchers.any(), anyInt());
    }
}
