package com.project.bangjjack.domain.chat.domain.service;

import com.project.bangjjack.domain.chat.application.exception.AlreadyLeftChatRoomException;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoom;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoomParticipant;
import com.project.bangjjack.domain.chat.domain.repository.ChatRoomParticipantRepository;
import com.project.bangjjack.domain.chat.domain.repository.ChatRoomRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChatStateUpdateServiceTest {

    private static final Long ROOM_ID = 10L;
    private static final Long USER_ID = 1L;
    private static final Long MESSAGE_ID = 100L;

    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private ChatRoomParticipantRepository participantRepository;
    @Mock private EntityManager entityManager;
    @InjectMocks private ChatStateUpdateService service;

    private ChatRoomParticipant participant;

    @BeforeEach
    void setUp() {
        ChatRoom room = ChatRoom.createDirect(USER_ID, "DM_1_2");
        participant = ChatRoomParticipant.create(room, USER_ID);
        given(chatRoomRepository.findByIdForUpdate(ROOM_ID)).willReturn(Optional.of(room));
        given(participantRepository.findParticipantForUpdate(ROOM_ID, USER_ID))
                .willReturn(Optional.of(participant));
    }

    @Test
    @DisplayName("전송 준비는 방 락, 발신자 락 조회 및 refresh, 상대 재입장 UPDATE 순서로 실행한다")
    void prepareUserMessage_locksRoomAndSenderBeforeRejoiningOthers() {
        service.prepareUserMessage(ROOM_ID, USER_ID);

        InOrder order = inOrder(chatRoomRepository, participantRepository, entityManager);
        verifyRoomAndParticipantLock(order);
        order.verify(participantRepository).rejoinOthers(ROOM_ID, USER_ID);
    }

    @Test
    @DisplayName("퇴장은 방 락, 참여자 락 조회, 퇴장 UPDATE 순서로 실행한다")
    void leave_locksRoomAndParticipantBeforeUpdating() {
        service.leave(ROOM_ID, USER_ID);

        InOrder order = inOrder(chatRoomRepository, participantRepository, entityManager);
        verifyRoomAndParticipantLock(order);
        order.verify(participantRepository).leave(ROOM_ID, USER_ID);
        order.verify(entityManager).refresh(participant, LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    @DisplayName("읽음 처리는 방 락, 참여자 락 조회, 읽음 UPDATE 순서로 실행한다")
    void markAsRead_locksRoomAndParticipantBeforeUpdating() {
        given(participantRepository.markAsRead(ROOM_ID, USER_ID, MESSAGE_ID)).willReturn(1);

        assertThat(service.markAsRead(ROOM_ID, USER_ID, MESSAGE_ID)).isTrue();

        InOrder order = inOrder(chatRoomRepository, participantRepository, entityManager);
        verifyRoomAndParticipantLock(order);
        order.verify(participantRepository).markAsRead(ROOM_ID, USER_ID, MESSAGE_ID);
        order.verify(entityManager).refresh(participant, LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    @DisplayName("퇴장한 발신자는 예외를 발생시키고 상대 재입장 UPDATE를 실행하지 않는다")
    void prepareUserMessage_leftSenderDoesNotRejoinOthers() {
        participant.leave(MESSAGE_ID);

        assertThatThrownBy(() -> service.prepareUserMessage(ROOM_ID, USER_ID))
                .isInstanceOf(AlreadyLeftChatRoomException.class);

        InOrder order = inOrder(chatRoomRepository, participantRepository, entityManager);
        verifyRoomAndParticipantLock(order);
        verify(participantRepository, never()).rejoinOthers(ROOM_ID, USER_ID);
    }

    private void verifyRoomAndParticipantLock(InOrder order) {
        order.verify(chatRoomRepository).findByIdForUpdate(ROOM_ID);
        order.verify(participantRepository).findParticipantForUpdate(ROOM_ID, USER_ID);
        order.verify(entityManager).refresh(participant, LockModeType.PESSIMISTIC_WRITE);
    }
}
