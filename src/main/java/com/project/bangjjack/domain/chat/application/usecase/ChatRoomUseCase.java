package com.project.bangjjack.domain.chat.application.usecase;

import com.project.bangjjack.domain.chat.application.dto.ChatRoomCursor;
import com.project.bangjjack.domain.chat.application.dto.request.CreateChatRoomRequest;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoomCategory;
import com.project.bangjjack.domain.chat.application.dto.response.ChatRoomListResponse;
import com.project.bangjjack.domain.chat.application.dto.response.ChatRoomResponse;
import com.project.bangjjack.domain.chat.application.dto.response.ChatRoomSummaryResponse;
import com.project.bangjjack.domain.chat.application.event.ChatRoomCreatedEvent;
import com.project.bangjjack.domain.chat.application.exception.CannotChatWithSelfException;
import com.project.bangjjack.domain.chat.domain.entity.Chat;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoom;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoomParticipant;
import com.project.bangjjack.domain.chat.domain.service.ChatMessageGetService;
import com.project.bangjjack.domain.chat.domain.service.ChatRoomCreateService;
import com.project.bangjjack.domain.chat.domain.service.ChatRoomGetService;
import com.project.bangjjack.domain.user.domain.entity.User;
import com.project.bangjjack.domain.user.domain.service.UserGetService;
import com.project.bangjjack.domain.chat.domain.service.ChatStateUpdateService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomUseCase {

    private final ChatRoomCreateService chatRoomCreateService;
    private final ChatStateUpdateService chatStateUpdateService;
    private final ChatRoomGetService chatRoomGetService;
    private final ChatMessageGetService chatMessageGetService;
    private final UserGetService userGetService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public ChatRoomResponse createDirectRoom(Long currentUserId, CreateChatRoomRequest request) {
        if (currentUserId.equals(request.targetUserId())) {
            throw new CannotChatWithSelfException();
        }
        userGetService.getById(request.targetUserId());

        String directRoomKey = chatRoomCreateService.createDirectKey(currentUserId, request.targetUserId());

        Optional<ChatRoom> existing = chatRoomGetService.findByDirectRoomKey(directRoomKey);
        if (existing.isPresent()) {
            ChatRoom chatRoom = existing.get();
            List<ChatRoomParticipant> participants = chatStateUpdateService
                    .rejoinAndGetParticipants(chatRoom.getId(), currentUserId);
            return ChatRoomResponse.from(chatRoom, participants, false);
        }

        // TODO: 최초 방 생성 시 동시 요청이 들어오면 directRoomKey unique 충돌로 500 가능. 필요 시 Redis 분산락 적용
        ChatRoom chatRoom = chatRoomCreateService.createDirectRoom(currentUserId, request.targetUserId(), directRoomKey);
        List<ChatRoomParticipant> participants = chatRoomGetService.findParticipantsByRoomId(chatRoom.getId());
        eventPublisher.publishEvent(new ChatRoomCreatedEvent(chatRoom.getId(), List.of(currentUserId, request.targetUserId())));
        return ChatRoomResponse.from(chatRoom, participants, true);
    }

    public ChatRoomListResponse getMyChatRooms(Long userId, ChatRoomCategory category, String cursor, int size) {
        ChatRoomCursor cursorInfo = decodeCursor(cursor);
        List<ChatRoomParticipant> myParticipants = chatRoomGetService.findMyDirectParticipantsPage(
                userId, cursorInfo.roomId(), cursorInfo.lastMessageAt(), size, category);

        boolean hasNext = myParticipants.size() > size;
        List<ChatRoomParticipant> page = hasNext ? myParticipants.subList(0, size) : myParticipants;
        if (page.isEmpty()) {
            return ChatRoomListResponse.from(List.of(), null, false);
        }

        List<Long> roomIds = page.stream().map(p -> p.getChatRoom().getId()).toList();
        Map<Long, Long> partnerIdByRoomId = chatRoomGetService.findPartnerIdsByDirectRoomIds(roomIds, userId);
        Map<Long, User> partnerMap = userGetService.getByIds(partnerIdByRoomId.values());
        Map<Long, Chat> lastMessageMap = chatMessageGetService.findLastMessagesByRoomIds(roomIds);

        List<ChatRoomSummaryResponse> rooms = buildChatRoomSummaries(page, partnerIdByRoomId, partnerMap, lastMessageMap);
        String nextCursor = hasNext ? buildNextCursor(page) : null;
        return ChatRoomListResponse.from(rooms, nextCursor, hasNext);
    }

    private ChatRoomCursor decodeCursor(String cursor) {
        if (cursor == null) {
            return new ChatRoomCursor(null, null);
        }
        return ChatRoomCursor.decode(cursor);
    }

    private List<ChatRoomSummaryResponse> buildChatRoomSummaries(
            List<ChatRoomParticipant> page,
            Map<Long, Long> partnerIdByRoomId,
            Map<Long, User> partnerMap,
            Map<Long, Chat> lastMessageMap
    ) {
        List<ChatRoomSummaryResponse> rooms = new ArrayList<>();
        for (ChatRoomParticipant participant : page) {
            ChatRoom room = participant.getChatRoom();
            Long partnerId = partnerIdByRoomId.get(room.getId());
            User partner = partnerId != null ? partnerMap.get(partnerId) : null;
            Chat lastChat = lastMessageMap.get(room.getId());
            rooms.add(ChatRoomSummaryResponse.from(room, partner, lastChat, participant.getUnreadCount()));
        }
        return rooms;
    }

    private String buildNextCursor(List<ChatRoomParticipant> page) {
        ChatRoom lastRoom = page.get(page.size() - 1).getChatRoom();
        return new ChatRoomCursor(lastRoom.getId(), lastRoom.getLastMessageAt()).encode();
    }

    public List<Long> getMyRoomIds(Long userId) {
        return chatRoomGetService.findRoomIdsByUserId(userId);
    }

    public void validateParticipant(Long roomId, Long userId) {
        chatRoomGetService.validateParticipant(roomId, userId);
    }

    @Transactional
    public void leaveChatRoom(Long roomId, Long userId) {
        chatStateUpdateService.leave(roomId, userId);
    }
}
