package com.project.bangjjack.domain.chat.infrastructure.event;

import com.project.bangjjack.domain.chat.application.event.ChatMessageSentEvent;
import com.project.bangjjack.domain.chat.infrastructure.pubsub.ChatMessagePublisher;
import com.project.bangjjack.domain.chat.infrastructure.pubsub.PubSubPublishExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 커밋 후 Redis 발행을 전용 실행기에 넘긴다.
 * AFTER_COMMIT 리스너는 DB 커넥션 반납 전에 실행되므로, 여기서 직접 발행하면
 * Redis 장애 시 발행 재시도 동안 커넥션을 점유해 커넥션 풀이 고갈된다.
 */
@Component
@RequiredArgsConstructor
public class ChatMessageEventListener {

    private final ChatMessagePublisher chatMessagePublisher;
    private final PubSubPublishExecutor publishExecutor;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(ChatMessageSentEvent event) {
        publishExecutor.execute(event.roomId(),
                () -> chatMessagePublisher.publish(event.roomId(), event.broadcast()));
    }
}
