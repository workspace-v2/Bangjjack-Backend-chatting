package com.project.bangjjack.domain.chat.infrastructure.event;

import com.project.bangjjack.domain.chat.application.dto.response.ChatMessageBroadcast;
import com.project.bangjjack.domain.chat.application.dto.response.WebSocketBroadcastType;
import com.project.bangjjack.domain.chat.application.event.ChatMessageSentEvent;
import com.project.bangjjack.domain.chat.domain.entity.MessageType;
import com.project.bangjjack.domain.chat.infrastructure.pubsub.ChatMessagePublisher;
import com.project.bangjjack.domain.chat.infrastructure.pubsub.PubSubPublishExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@DisplayName("ChatMessageEventListener")
class ChatMessageEventListenerTest {

    private final PubSubPublishExecutor executor = new PubSubPublishExecutor(new SimpleMeterRegistry(), 2, 100);

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.destroy();
    }

    @Test
    @DisplayName("Redis 발행이 멈춰 있어도 커밋 후 리스너는 즉시 반환하고, 발행은 별도 스레드에서 실행된다")
    void handle_returnsImmediately_whilePublishBlocked() throws InterruptedException {
        ChatMessagePublisher publisher = mock(ChatMessagePublisher.class);
        CountDownLatch redisRecovered = new CountDownLatch(1);
        doAnswer(invocation -> {
            redisRecovered.await(); // Redis 장애: 발행이 끝나지 않음
            return null;
        }).when(publisher).publish(eq(10L), any());
        ChatMessageEventListener listener = new ChatMessageEventListener(publisher, executor);
        ChatMessageBroadcast broadcast = new ChatMessageBroadcast(
                WebSocketBroadcastType.CHAT_MESSAGE, 100L, 10L, 2L, "안녕하세요",
                MessageType.USER, null, LocalDateTime.of(2026, 11, 2, 12, 0));

        long start = System.nanoTime();
        listener.handle(new ChatMessageSentEvent(10L, broadcast));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(elapsedMs).isLessThan(100); // 이 스레드가 쥔 DB 커넥션이 바로 반납될 수 있다
        verify(publisher, timeout(1000)).publish(10L, broadcast);
        redisRecovered.countDown();
    }
}
