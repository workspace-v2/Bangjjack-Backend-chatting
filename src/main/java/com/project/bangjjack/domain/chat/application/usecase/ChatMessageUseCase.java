package com.project.bangjjack.domain.chat.application.usecase;

import com.project.bangjjack.domain.chat.application.dto.request.SendChatMessageRequest;
import com.project.bangjjack.domain.chat.application.dto.response.ChatMessageBroadcast;
import com.project.bangjjack.domain.chat.application.dto.response.ChatMessagePageResponse;
import com.project.bangjjack.domain.chat.application.dto.response.ChatMessageResponse;
import com.project.bangjjack.domain.chat.application.event.ChatMessageSentEvent;
import com.project.bangjjack.domain.chat.application.event.ReadReceiptEvent;
import com.project.bangjjack.domain.chat.application.exception.InvalidMessageContentException;
import com.project.bangjjack.domain.chat.domain.entity.Chat;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoom;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoomParticipant;
import com.project.bangjjack.domain.chat.domain.service.ChatMessageGetService;
import com.project.bangjjack.domain.chat.domain.service.ChatRoomGetService;
import com.project.bangjjack.domain.chat.domain.service.ChatSaveService;
import com.project.bangjjack.domain.chat.domain.service.ChatStateUpdateService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatMessageUseCase {

    private final ChatRoomGetService chatRoomGetService;
    private final ChatSaveService chatSaveService;
    private final ChatStateUpdateService chatStateUpdateService;
    private final ChatMessageGetService chatMessageGetService;
    private final ApplicationEventPublisher eventPublisher;
    private final Validator validator;

    @Transactional
    public void sendMessage(Long senderId, Long roomId, SendChatMessageRequest request) {
        if (request == null) {
            throw new InvalidMessageContentException();
        }
        // WebSocket은 @Valid 자동 적용 대상이 아니므로 수동 검증
        Set<ConstraintViolation<SendChatMessageRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new InvalidMessageContentException();
        }
        // TODO: 운영 환경 전환 시 content 제거 또는 contentLength로 변경 (PII)
        log.debug("[채팅 송신] 시작 - senderId={}, roomId={}, content='{}'", senderId, roomId, request.content());

        ChatRoom chatRoom = chatRoomGetService.getByIdAndValidateParticipant(roomId, senderId);

        Chat savedChat = chatSaveService.saveWithRejoin(senderId, chatRoom, request.content());
        log.debug("[채팅 송신] DB 저장 완료 - messageId={}", savedChat.getId());

        ChatMessageBroadcast broadcast = ChatMessageBroadcast.from(savedChat, roomId);
        eventPublisher.publishEvent(new ChatMessageSentEvent(roomId, broadcast));
        log.debug("[채팅 송신] 브로드캐스트 이벤트 발행 완료 - messageId={}, roomId={}", savedChat.getId(), roomId);
    }

    @Transactional
    public void markAsRead(Long userId, Long roomId, Long messageId) {
        markAsReadAndPublish(roomId, userId, messageId);
    }

    @Transactional
    public ChatMessagePageResponse getMessages(Long currentUserId, Long roomId, Long cursor, int size) {
        ChatRoomParticipant participant = chatRoomGetService.getActiveParticipant(roomId, currentUserId);

        List<Chat> fetched = chatMessageGetService.getMessages(roomId, cursor, participant.getVisibleFromMessageId(), size);

        boolean hasNext = fetched.size() > size;
        List<Chat> content = hasNext ? fetched.subList(0, size) : fetched;

        if (!content.isEmpty()) {
            Long newestId = content.get(0).getId();
            Long lastReadId = participant.getLastReadMessageId();
            // 커서는 단조 증가하므로 이미 읽은 페이지는 방 락 없이 건너뛴다.
            // 스냅샷이 오래돼 커서가 작게 보이면 기존 락과 조건부 UPDATE가 최종 판단한다.
            if (lastReadId == null || lastReadId < newestId) {
                markAsReadAndPublish(roomId, currentUserId, newestId);
            }
        }
        Long nextCursor = hasNext ? content.get(content.size() - 1).getId() : null;

        List<ChatMessageResponse> messages = content.stream()
                .map(ChatMessageResponse::from)
                .toList();

        Long partnerLastReadMessageId = chatRoomGetService.findPartnerLastReadMessageId(roomId, currentUserId);

        return ChatMessagePageResponse.of(messages, nextCursor, hasNext, partnerLastReadMessageId);
    }

    private void markAsReadAndPublish(Long roomId, Long userId, Long messageId) {
        if (chatStateUpdateService.markAsRead(roomId, userId, messageId)) {
            eventPublisher.publishEvent(new ReadReceiptEvent(roomId, userId, messageId));
        }
    }
}
