package com.project.bangjjack.domain.chat.infrastructure.event;

import com.project.bangjjack.domain.chat.application.dto.response.ReadReceiptBroadcast;
import com.project.bangjjack.domain.chat.application.event.ReadReceiptEvent;
import com.project.bangjjack.domain.chat.infrastructure.pubsub.PubSubPublishExecutor;
import com.project.bangjjack.domain.chat.infrastructure.pubsub.ReadReceiptPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 커밋 후 읽음 알림 발행을 전용 실행기에 넘긴다 (ChatMessageEventListener 와 같은 이유).
 * 같은 방의 메시지 발행과 같은 레인에서 실행되어 방 안의 발행 순서가 유지된다.
 */
@Component
@RequiredArgsConstructor
public class ReadReceiptEventListener {

    private final ReadReceiptPublisher readReceiptPublisher;
    private final PubSubPublishExecutor publishExecutor;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(ReadReceiptEvent event) {
        ReadReceiptBroadcast broadcast = ReadReceiptBroadcast.of(event.roomId(), event.readerId(), event.lastReadMessageId());
        publishExecutor.execute(event.roomId(), () -> readReceiptPublisher.publish(event.roomId(), broadcast));
    }
}
