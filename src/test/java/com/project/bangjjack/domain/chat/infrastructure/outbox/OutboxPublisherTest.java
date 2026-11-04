package com.project.bangjjack.domain.chat.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.bangjjack.domain.chat.infrastructure.event.RoomCreatedPublisher;
import com.project.bangjjack.global.infrastructure.JsonSerializer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("OutboxPublisher - 미발행 이벤트 재발행")
class OutboxPublisherTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final RoomCreatedPublisher roomCreatedPublisher = mock(RoomCreatedPublisher.class);
    private final JsonSerializer jsonSerializer = new JsonSerializer(new ObjectMapper());
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final OutboxPublisher publisher =
            new OutboxPublisher(repository, roomCreatedPublisher, jsonSerializer, meterRegistry, 3000, 100, 60);

    private OutboxEvent roomCreatedRow(long roomId) {
        return OutboxEvent.roomCreated(roomId, "{\"roomId\":" + roomId + ",\"participantIds\":[1,2]}");
    }

    @Test
    @DisplayName("미발행 이벤트를 delayed 로 발행하고 발행됨으로 표시한다")
    void publishesAndMarks() {
        OutboxEvent row = roomCreatedRow(10L);
        given(repository.findUnpublishedForUpdate(any(), anyInt())).willReturn(List.of(row));

        publisher.publishPending();

        verify(roomCreatedPublisher).publish(argThat(e -> e.roomId().equals(10L) && e.delayed()));
        assertThat(row.isPublished()).isTrue();
        assertThat(meterRegistry.counter(OutboxPublisher.RECOVERED_METRIC).count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("발행 중 실패하면 그 주기를 멈추고, 실패 이전 행만 표시한다")
    void stopsOnFailure() {
        OutboxEvent first = roomCreatedRow(10L);
        OutboxEvent second = roomCreatedRow(20L);
        OutboxEvent third = roomCreatedRow(30L);
        given(repository.findUnpublishedForUpdate(any(), anyInt())).willReturn(List.of(first, second, third));
        doThrow(new RuntimeException("redis down")).when(roomCreatedPublisher)
                .publish(argThat(e -> e.roomId().equals(20L)));

        publisher.publishPending();

        assertThat(first.isPublished()).isTrue();
        assertThat(second.isPublished()).isFalse();
        assertThat(third.isPublished()).isFalse();
        verify(roomCreatedPublisher, never()).publish(argThat(e -> e.roomId().equals(30L)));
        assertThat(meterRegistry.counter(OutboxPublisher.RECOVERED_METRIC).count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("미발행 이벤트가 없으면 아무것도 발행하지 않는다")
    void noopWhenEmpty() {
        given(repository.findUnpublishedForUpdate(any(), anyInt())).willReturn(List.of());

        publisher.publishPending();

        verify(roomCreatedPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("정리: 보관 기간이 지난 발행 완료 행을 삭제한다")
    void cleanupDeletesOld() {
        given(repository.deletePublishedBefore(any())).willReturn(5);

        publisher.cleanup();

        verify(repository).deletePublishedBefore(any());
    }
}
