package com.project.bangjjack.domain.chat.infrastructure.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.bangjjack.domain.chat.application.event.ChatRoomCreatedEvent;
import com.project.bangjjack.domain.chat.infrastructure.outbox.OutboxEvent;
import com.project.bangjjack.domain.chat.infrastructure.outbox.OutboxEventRepository;
import com.project.bangjjack.domain.chat.infrastructure.pubsub.PubSubPublishExecutor;
import com.project.bangjjack.global.infrastructure.JsonSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("ChatRoomCreatedEventListener - Outbox 저장 + 즉시 발행")
class ChatRoomCreatedEventListenerTest {

    private static final ChatRoomCreatedEvent EVENT = new ChatRoomCreatedEvent(10L, List.of(1L, 2L));

    private final OutboxEventRepository outboxRepository = mock(OutboxEventRepository.class);
    private final RoomCreatedPublisher publisher = mock(RoomCreatedPublisher.class);
    private final PubSubPublishExecutor executor = mock(PubSubPublishExecutor.class);
    private final JsonSerializer jsonSerializer = new JsonSerializer(new ObjectMapper());
    private final ChatRoomCreatedEventListener listener =
            new ChatRoomCreatedEventListener(outboxRepository, publisher, executor, jsonSerializer);

    @BeforeEach
    void setUp() {
        // 실행기에 넘긴 작업을 즉시 실행한다
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return true;
        }).when(executor).execute(anyLong(), any(Runnable.class));
    }

    @Test
    @DisplayName("커밋 전: 방 생성 이벤트를 Outbox 에 저장한다 (payload 에 delayed 없음)")
    void recordSavesOutbox() {
        listener.record(EVENT);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        OutboxEvent saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo(OutboxEvent.TYPE_ROOM_CREATED);
        assertThat(saved.getRoomId()).isEqualTo(10L);
        assertThat(saved.getPayload()).contains("\"roomId\":10").doesNotContain("delayed");
    }

    @Test
    @DisplayName("커밋 후: 즉시 발행에 성공하면 Outbox 를 발행됨으로 표시한다")
    void publishImmediatelyMarksPublishedOnSuccess() {
        listener.publishImmediately(EVENT);

        verify(publisher).publish(EVENT);
        verify(outboxRepository).markRoomCreatedPublished(eq(10L), any());
    }

    @Test
    @DisplayName("커밋 후: 즉시 발행이 실패하면 표시하지 않는다 (폴러가 재발행)")
    void publishImmediatelyLeavesUnmarkedOnFailure() {
        doThrow(new RuntimeException("redis down")).when(publisher).publish(any());

        listener.publishImmediately(EVENT);

        verify(publisher).publish(EVENT);
        verify(outboxRepository, never()).markRoomCreatedPublished(anyLong(), any());
    }
}
