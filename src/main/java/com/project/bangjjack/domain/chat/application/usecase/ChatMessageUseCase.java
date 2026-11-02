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
import com.project.bangjjack.domain.chat.domain.entity.MessageType;
import com.project.bangjjack.domain.chat.domain.service.ChatMessageGetService;
import com.project.bangjjack.domain.chat.domain.service.ChatRoomGetService;
import com.project.bangjjack.domain.chat.domain.service.ChatSaveService;
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

        List<ChatRoomParticipant> leftParticipants = chatRoomGetService.findLeftParticipantsByRoomId(roomId, senderId);
        if (!leftParticipants.isEmpty()) {
            // 재입장은 participant 행을 수정하므로, 데드락 방지를 위해 채팅방 락을 먼저 잡는다 (ChatSaveService 락 순서)
            chatSaveService.lockRoom(roomId);
            leftParticipants.forEach(ChatRoomParticipant::rejoin);
        }

        Chat savedChat = chatSaveService.save(senderId, chatRoom, request.content(), MessageType.USER);
        log.debug("[채팅 송신] DB 저장 완료 - messageId={}", savedChat.getId());

        ChatMessageBroadcast broadcast = ChatMessageBroadcast.from(savedChat, roomId);
        eventPublisher.publishEvent(new ChatMessageSentEvent(roomId, broadcast));
        log.debug("[채팅 송신] 브로드캐스트 이벤트 발행 완료 - messageId={}, roomId={}", savedChat.getId(), roomId);
    }

    @Transactional
    public void markAsRead(Long userId, Long roomId, Long messageId) {
        // messageId가 해당 roomId 소속인지 별도 검증하지 않음.
        // 호출자는 이미 인증 + 참여자 검증을 통과한 유저이며, 잘못된 messageId를 보내도
        // 본인의 lastReadMessageId만 오염되고 다음 정상 READ에서 자연 복구됨.
        // 매 호출마다 DB 조회를 추가하는 비용 대비 실질적 위협이 낮아 의도적으로 생략.
        ChatRoomParticipant participant = chatRoomGetService.getActiveParticipant(roomId, userId);
        markAsReadAndPublish(participant, roomId, userId, messageId);
    }

    @Transactional
    public ChatMessagePageResponse getMessages(Long currentUserId, Long roomId, Long cursor, int size) {
        ChatRoomParticipant participant = chatRoomGetService.getActiveParticipant(roomId, currentUserId);

        List<Chat> fetched = chatMessageGetService.getMessages(roomId, cursor, participant.getVisibleFromMessageId(), size);

        boolean hasNext = fetched.size() > size;
        List<Chat> content = hasNext ? fetched.subList(0, size) : fetched;

        if (!content.isEmpty()) {
            markAsReadAndPublish(participant, roomId, currentUserId, content.get(0).getId());
        }
        Long nextCursor = hasNext ? content.get(content.size() - 1).getId() : null;

        List<ChatMessageResponse> messages = content.stream()
                .map(ChatMessageResponse::from)
                .toList();

        Long partnerLastReadMessageId = chatRoomGetService.findPartnerLastReadMessageId(roomId, currentUserId);

        return ChatMessagePageResponse.of(messages, nextCursor, hasNext, partnerLastReadMessageId);
    }

    private void markAsReadAndPublish(ChatRoomParticipant participant, Long roomId, Long userId, Long messageId) {
        // 동시 READ 요청 시 두 트랜잭션이 모두 true를 반환해 이벤트가 2회 발행될 수 있음.
        // 읽음 처리는 최종 상태가 동일하므로 낙관적 락 없이 허용하며,
        // 클라이언트에서 동일 {readerId, messageId} 수신 시 중복 무시로 대응.
        if (participant.markAsRead(messageId)) {
            eventPublisher.publishEvent(new ReadReceiptEvent(roomId, userId, messageId));
        }
    }
}
