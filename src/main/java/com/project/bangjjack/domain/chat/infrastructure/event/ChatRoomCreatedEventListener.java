package com.project.bangjjack.domain.chat.infrastructure.event;

import com.project.bangjjack.domain.chat.application.event.ChatRoomCreatedEvent;
import com.project.bangjjack.domain.chat.infrastructure.outbox.OutboxEvent;
import com.project.bangjjack.domain.chat.infrastructure.outbox.OutboxEventRepository;
import com.project.bangjjack.domain.chat.infrastructure.pubsub.PubSubPublishExecutor;
import com.project.bangjjack.global.infrastructure.JsonSerializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;

/**
 * 채팅방 생성 이벤트 발행 (Transactional Outbox).
 *
 * 이 이벤트를 놓치면 참여자는 재연결 전까지 새 방 메시지를 받지 못한다. Redis 장애로 발행이 실패해도 유실되지 않도록,
 * 방 생성 트랜잭션 안에서 Outbox 에 이벤트를 저장(방과 원자적 커밋)하고, 커밋 직후 즉시 발행한다(지연 없는 빠른 길).
 * 즉시 발행이 성공하면 Outbox 를 발행됨으로 표시하고, 실패하면 폴러(OutboxPublisher)가 Redis 복구 때까지 재발행한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatRoomCreatedEventListener {

    private final OutboxEventRepository outboxRepository;
    private final RoomCreatedPublisher publisher;
    private final PubSubPublishExecutor publishExecutor;
    private final JsonSerializer jsonSerializer;

    /** 커밋 전: 방과 같은 트랜잭션에서 Outbox 에 저장한다. 저장 실패 시 방 생성도 롤백된다(원자성). */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void record(ChatRoomCreatedEvent event) {
        String payload = jsonSerializer.serialize(event); // delayed 기본값(false)은 JSON 에서 제외됨
        outboxRepository.save(OutboxEvent.roomCreated(event.roomId(), payload));
    }

    /** 커밋 후: 즉시 발행(DB 커넥션 반납 뒤 전용 실행기에서). 성공하면 발행됨으로 표시, 실패는 폴러가 처리. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishImmediately(ChatRoomCreatedEvent event) {
        publishExecutor.execute(event.roomId(), () -> {
            try {
                publisher.publish(event);
                outboxRepository.markRoomCreatedPublished(event.roomId(), LocalDateTime.now());
            } catch (Exception e) {
                // 발행 실패(대부분 Redis 장애) → Outbox 미발행 상태로 남아 폴러가 재발행한다
                log.warn("[Outbox] 방 생성 이벤트 즉시 발행 실패, 폴러가 재발행 - roomId={}, 원인={}",
                        event.roomId(), e.getMessage());
            }
        });
    }
}
