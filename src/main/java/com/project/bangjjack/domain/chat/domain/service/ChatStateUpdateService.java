package com.project.bangjjack.domain.chat.domain.service;

import com.project.bangjjack.domain.chat.application.exception.AlreadyLeftChatRoomException;
import com.project.bangjjack.domain.chat.application.exception.ChatRoomNotFoundException;
import com.project.bangjjack.domain.chat.application.exception.NotChatParticipantException;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoomCategory;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoomParticipant;
import com.project.bangjjack.domain.chat.domain.repository.ChatRoomParticipantRepository;
import com.project.bangjjack.domain.chat.domain.repository.ChatRoomRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 채팅 상태 변경은 방 락 -> 참여자 순서로 직렬화하고 필요한 컬럼만 명시적으로 갱신한다.
 * 벌크 UPDATE는 관리 엔티티를 갱신하지 않으므로, 이후 사용하는 엔티티만 refresh한다.
 * 다른 도메인의 미반영 변경이 있는 트랜잭션에도 참여하므로 영속성 컨텍스트 전체를 clear하지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ChatStateUpdateService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomParticipantRepository participantRepository;
    private final EntityManager entityManager;

    // 같은 패키지의 메시지 저장 서비스도 반드시 트랜잭션 안에서 이 락을 먼저 획득한다.
    void lockRoom(Long roomId) {
        chatRoomRepository.findByIdForUpdate(roomId).orElseThrow(ChatRoomNotFoundException::new);
    }

    /**
     * 방의 다음 메시지 seq 를 발급한다. 호출 전에 반드시 lockRoom 으로 방 락을 잡아야 한다
     * (같은 방의 저장이 직렬화되므로 seq 가 빈틈없이 증가하고, 커밋 순서와 seq 순서가 같다).
     */
    long nextSeq(Long roomId) {
        chatRoomRepository.incrementLastSeq(roomId);
        return chatRoomRepository.findLastSeq(roomId).orElseThrow(ChatRoomNotFoundException::new);
    }

    void prepareUserMessage(Long roomId, Long senderId) {
        lockRoom(roomId);
        getActiveParticipantForUpdate(roomId, senderId);
        participantRepository.rejoinOthers(roomId, senderId);
    }

    public boolean markAsRead(Long roomId, Long userId, Long messageId) {
        lockRoom(roomId);
        ChatRoomParticipant participant = getActiveParticipantForUpdate(roomId, userId);
        boolean updated = participantRepository.markAsRead(roomId, userId, messageId) == 1;
        if (updated) {
            entityManager.refresh(participant, LockModeType.PESSIMISTIC_WRITE);
        }
        return updated;
    }

    public void leave(Long roomId, Long userId) {
        lockRoom(roomId);
        ChatRoomParticipant participant = getActiveParticipantForUpdate(roomId, userId);
        participantRepository.leave(roomId, userId);
        entityManager.refresh(participant, LockModeType.PESSIMISTIC_WRITE);
    }

    public List<ChatRoomParticipant> rejoinAndGetParticipants(Long roomId, Long userId) {
        lockRoom(roomId);
        participantRepository.rejoin(roomId, userId);
        List<ChatRoomParticipant> participants = participantRepository.findParticipantsForUpdate(roomId);
        participants.forEach(p -> entityManager.refresh(p, LockModeType.PESSIMISTIC_WRITE));
        return participants;
    }

    public void updateCategory(Long roomId, ChatRoomCategory category) {
        if (category == null) {
            throw new IllegalArgumentException("category는 null일 수 없습니다.");
        }
        lockRoom(roomId);
        chatRoomRepository.updateCategory(roomId, category);
    }

    private ChatRoomParticipant getActiveParticipantForUpdate(Long roomId, Long userId) {
        ChatRoomParticipant participant = participantRepository.findParticipantForUpdate(roomId, userId)
                .orElseThrow(NotChatParticipantException::new);
        // 락 조회만으로 기존 1차 캐시 엔티티가 새 값으로 교체되지는 않는다.
        entityManager.refresh(participant, LockModeType.PESSIMISTIC_WRITE);
        if (participant.isLeft()) {
            throw new AlreadyLeftChatRoomException();
        }
        return participant;
    }
}
