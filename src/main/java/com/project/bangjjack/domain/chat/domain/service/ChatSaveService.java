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
 * 같은 방의 메시지 저장은 chat_room 행 쓰기 락을 먼저 잡는다.
 * 사용자 메시지는 그 뒤 발신자 상태를 현재 읽기로 검증하고 LEFT 참여자를 재입장시키고, 메시지 저장과 unread 갱신을 수행한다.
 * 이전에는 INSERT 의 외래키 확인이 chat_room 에 공유(S) 락을 먼저 잡고, 같은 방의 다른 트랜잭션이 participant 행 락을
 * 잡은 상태에서 서로 반대 순서로 상대 락을 기다려 데드락이 발생했다 (MySQL 데드락 로그로 확인).
 * 이 서비스에서 참여자를 조회하거나 변경하기 전에 채팅방 락을 잡아 같은 방의 저장을 직렬화한다.
 * 방별 seq 도 같은 락 안에서 발급하므로 추가 경합 없이 빈틈없는 순번이 보장된다.
 */
@Service
@RequiredArgsConstructor
public class ChatSaveService {

    private final ChatRepository chatRepository;
    private final ChatStateUpdateService chatStateUpdateService;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomParticipantRepository chatRoomParticipantRepository;

    @Transactional
    public Chat save(Long senderId, ChatRoom chatRoom, String content, MessageType messageType) {
        return save(senderId, chatRoom, content, messageType, null);
    }

    @Transactional
    public Chat save(Long senderId, ChatRoom chatRoom, String content, MessageType messageType, Long applicationId) {
        chatStateUpdateService.lockRoom(chatRoom.getId());
        return persistMessage(senderId, chatRoom, content, messageType, applicationId);
    }

    /** 사용자 메시지의 재입장과 저장을 하나의 트랜잭션 및 락 규칙으로 처리한다. */
    @Transactional
    public Chat saveWithRejoin(Long senderId, ChatRoom chatRoom, String content) {
        chatStateUpdateService.prepareUserMessage(chatRoom.getId(), senderId);
        return persistMessage(senderId, chatRoom, content, MessageType.USER, null);
    }

    private Chat persistMessage(Long senderId, ChatRoom chatRoom, String content,
                               MessageType messageType, Long applicationId) {
        long seq = chatStateUpdateService.nextSeq(chatRoom.getId());
        Chat saved = chatRepository.save(Chat.create(senderId, chatRoom, content, messageType, applicationId, seq));
        chatRoomRepository.updateLastMessage(chatRoom.getId(), saved.getId(), saved.getCreatedAt());
        chatRoomParticipantRepository.incrementUnreadCountExcludingSender(chatRoom.getId(), senderId);
        return saved;
    }

}
