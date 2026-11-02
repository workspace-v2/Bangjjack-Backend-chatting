package com.project.bangjjack.domain.chat.domain.service;

import com.project.bangjjack.domain.chat.domain.entity.Chat;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoom;
import com.project.bangjjack.domain.chat.domain.entity.MessageType;
import com.project.bangjjack.domain.chat.domain.repository.ChatRepository;
import com.project.bangjjack.domain.chat.domain.repository.ChatRoomParticipantRepository;
import com.project.bangjjack.domain.chat.domain.repository.ChatRoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채팅 메시지 저장.
 *
 * 락 순서: chat_room 행 쓰기 락 -> chat INSERT -> chat_room_participant unread UPDATE -> chat_room last_message_at UPDATE(커밋 시)
 * 이전에는 INSERT 의 외래키 확인이 chat_room 에 공유(S) 락을 먼저 잡고, 같은 방의 다른 트랜잭션이 participant 행 락을
 * 잡은 상태에서 서로 반대 순서로 상대 락을 기다려 데드락이 발생했다 (MySQL 데드락 로그로 확인).
 * 트랜잭션 시작 시 chat_room 행 쓰기 락을 먼저 잡으면 같은 방의 저장이 줄을 서서 처리되어 순환 대기가 생기지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ChatSaveService {

    private final ChatRepository chatRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomParticipantRepository chatRoomParticipantRepository;

    @Transactional
    public Chat save(Long senderId, ChatRoom chatRoom, String content, MessageType messageType) {
        return save(senderId, chatRoom, content, messageType, null);
    }

    @Transactional
    public Chat save(Long senderId, ChatRoom chatRoom, String content, MessageType messageType, Long applicationId) {
        lockRoom(chatRoom.getId());
        Chat saved = chatRepository.save(Chat.create(senderId, chatRoom, content, messageType, applicationId));
        chatRoom.updateLastMessageAt(saved.getCreatedAt());
        chatRoomParticipantRepository.incrementUnreadCountExcludingSender(chatRoom.getId(), senderId);
        return saved;
    }

    /**
     * 같은 방의 저장을 직렬화하기 위해 chat_room 행 쓰기 락을 잡는다.
     * 같은 트랜잭션에서 participant 행을 먼저 수정해야 하는 경우(예: 나간 참여자 재입장)에는 그보다 먼저 호출한다.
     * 이미 락을 가진 트랜잭션에서 다시 호출해도 기다리지 않는다.
     */
    @Transactional
    public void lockRoom(Long roomId) {
        chatRoomRepository.findByIdForUpdate(roomId);
    }
}
