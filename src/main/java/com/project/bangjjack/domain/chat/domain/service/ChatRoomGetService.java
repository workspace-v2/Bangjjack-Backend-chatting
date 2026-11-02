package com.project.bangjjack.domain.chat.domain.service;

import com.project.bangjjack.domain.chat.application.exception.AlreadyLeftChatRoomException;
import com.project.bangjjack.domain.chat.application.exception.ChatRoomNotFoundException;
import com.project.bangjjack.domain.chat.application.exception.NotChatParticipantException;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoom;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoomCategory;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoomParticipant;
import com.project.bangjjack.domain.chat.domain.entity.ParticipantStatus;
import com.project.bangjjack.domain.chat.domain.repository.ChatRoomParticipantRepository;
import com.project.bangjjack.domain.chat.domain.repository.ChatRoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ChatRoomGetService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomParticipantRepository chatRoomParticipantRepository;

    public Optional<ChatRoom> findByDirectRoomKey(String directRoomKey) {
        return chatRoomRepository.findByDirectRoomKey(directRoomKey);
    }

    public ChatRoom getById(Long roomId) {
        return chatRoomRepository.findByIdAndDeletedFalse(roomId)
                .orElseThrow(ChatRoomNotFoundException::new);
    }

    public List<ChatRoomParticipant> findAllParticipantsByRoomId(Long roomId) {
        return chatRoomParticipantRepository.findByChatRoomIdAndDeletedFalse(roomId);
    }

    public List<ChatRoomParticipant> findParticipantsByRoomId(Long roomId) {
        return chatRoomParticipantRepository.findByChatRoomIdAndStatusAndDeletedFalse(roomId, ParticipantStatus.ACTIVE);
    }

    public List<ChatRoomParticipant> findLeftParticipantsByRoomId(Long roomId, Long excludeUserId) {
        return chatRoomParticipantRepository.findByChatRoomIdAndStatusAndDeletedFalse(roomId, ParticipantStatus.LEFT)
                .stream()
                .filter(p -> !p.getUserId().equals(excludeUserId))
                .toList();
    }

    public Optional<ChatRoomParticipant> findParticipantIncludingLeft(Long roomId, Long userId) {
        return chatRoomParticipantRepository.findByChatRoomIdAndUserIdAndDeletedFalse(roomId, userId);
    }

    public void validateParticipant(Long roomId, Long userId) {
        if (!chatRoomRepository.existsByIdAndDeletedFalse(roomId)) {
            throw new ChatRoomNotFoundException();
        }
        if (!chatRoomParticipantRepository.existsByChatRoomIdAndUserIdAndStatusAndDeletedFalse(roomId, userId, ParticipantStatus.ACTIVE)) {
            throw new NotChatParticipantException();
        }
    }

    public ChatRoom getByIdAndValidateParticipant(Long roomId, Long userId) {
        ChatRoom chatRoom = getById(roomId);
        if (!chatRoomParticipantRepository.existsByChatRoomIdAndUserIdAndStatusAndDeletedFalse(roomId, userId, ParticipantStatus.ACTIVE)) {
            throw new NotChatParticipantException();
        }
        return chatRoom;
    }

    public ChatRoomParticipant getActiveParticipant(Long roomId, Long userId) {
        ChatRoomParticipant participant = chatRoomParticipantRepository
                .findByChatRoomIdAndUserIdAndDeletedFalse(roomId, userId)
                .orElseThrow(() -> chatRoomRepository.existsByIdAndDeletedFalse(roomId)
                        ? new NotChatParticipantException()
                        : new ChatRoomNotFoundException());

        if (participant.isLeft()) {
            throw new AlreadyLeftChatRoomException();
        }
        return participant;
    }

    public List<Long> findRoomIdsByUserId(Long userId) {
        return chatRoomParticipantRepository.findAllRoomIdsByUserId(userId);
    }

    public List<ChatRoomParticipant> findMyDirectParticipantsPage(Long userId, Long cursorRoomId, LocalDateTime cursorLastMessageAt, int size, ChatRoomCategory category) {
        return chatRoomParticipantRepository.findMyDirectParticipantsWithCursor(userId, cursorRoomId, cursorLastMessageAt, size, category);
    }

    public Map<Long, Long> findPartnerIdsByDirectRoomIds(List<Long> roomIds, Long myUserId) {
        return chatRoomParticipantRepository.findPartnerIdsByDirectRoomIds(roomIds, myUserId);
    }

    public Long findPartnerLastReadMessageId(Long roomId, Long currentUserId) {
        return chatRoomParticipantRepository.findPartnerLastReadMessageId(roomId, currentUserId)
                .orElse(null);
    }
}
