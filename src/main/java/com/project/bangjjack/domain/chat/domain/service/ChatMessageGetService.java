package com.project.bangjjack.domain.chat.domain.service;

import com.project.bangjjack.domain.chat.domain.entity.Chat;
import com.project.bangjjack.domain.chat.domain.repository.ChatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatMessageGetService {

    private final ChatRepository chatRepository;

    public List<Chat> getMessages(Long roomId, Long cursor, Long visibleFromMessageId, int size) {
        long cursorId = cursor != null ? cursor : Long.MAX_VALUE;
        if (visibleFromMessageId != null) {
            return chatRepository.findByCursorPageAfterMessageId(roomId, cursorId, visibleFromMessageId, PageRequest.of(0, size + 1));
        }
        return chatRepository.findByCursorPage(roomId, cursorId, PageRequest.of(0, size + 1));
    }

    /** size + 1 개까지 조회해 다음 페이지 여부를 판단한다. */
    public List<Chat> getMessagesForSync(Long roomId, long afterSeq, long untilSeq, Long visibleFromMessageId, int size) {
        long visibleFrom = visibleFromMessageId != null ? visibleFromMessageId : 0L;
        return chatRepository.findSyncPage(roomId, afterSeq, untilSeq, visibleFrom, PageRequest.of(0, size + 1));
    }

    public Optional<Long> findLatestMessageId(Long roomId) {
        return chatRepository.findLatestMessageIdByRoomId(roomId);
    }

    public Map<Long, Chat> findLastMessagesByRoomIds(Collection<Long> roomIds) {
        return chatRepository.findLastMessagesByRoomIds(roomIds).stream()
                .collect(Collectors.toMap(c -> c.getChatRoom().getId(), c -> c));
    }
}
