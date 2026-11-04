package com.project.bangjjack.domain.chat.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.bangjjack.domain.chat.infrastructure.event.RoomCreatedPublisher;
import com.project.bangjjack.global.infrastructure.JsonSerializer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

class OutboxPublisherTest {
    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final OutboxClaimService claims = mock(OutboxClaimService.class);
    private final RoomCreatedPublisher redis = mock(RoomCreatedPublisher.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final OutboxPublisher publisher = new OutboxPublisher(repository, claims, redis,
            new JsonSerializer(new ObjectMapper()), meters, 3000, 100, 60, 60000);

    private OutboxClaimService.Claim row(long id, String type, String payload) {
        return new OutboxClaimService.Claim(id, id, type, payload, "token-" + id);
    }

    private OutboxClaimService.Claim valid(long id) {
        return row(id, "ROOM_CREATED", "{\"roomId\":" + id + ",\"participantIds\":[1,2]}");
    }

    @Test
    void publishesAfterClaimAndCompletesSeparately() {
        var row = valid(10);
        given(claims.claim(any(), anyLong())).willReturn(Optional.of(row), Optional.empty());
        given(claims.complete(row)).willReturn(true);
        publisher.publishPending();
        var order = inOrder(claims, redis);
        order.verify(claims).claim(any(), eq(60000L));
        order.verify(redis).publish(argThat(e -> e.roomId().equals(10L) && e.delayed()));
        order.verify(claims).complete(row);
        assertThat(meters.counter(OutboxPublisher.RECOVERED_METRIC).count()).isEqualTo(1);
    }

    @Test
    void redisFailureReleasesClaimAndStopsBeforeClaimingAnotherRow() {
        var row = valid(10);
        given(claims.claim(any(), anyLong())).willReturn(Optional.of(row));
        doThrow(new RuntimeException("redis down")).when(redis).publish(any());
        publisher.publishPending();
        verify(claims).release(row);
        verify(claims, never()).complete(any());
        verify(claims, never()).fail(any(), any());
        verify(claims, times(1)).claim(any(), anyLong());
    }

    @Test
    void completionFailureKeepsLeaseForRecovery() {
        var row = valid(10);
        given(claims.claim(any(), anyLong())).willReturn(Optional.of(row));
        given(claims.complete(row)).willThrow(new IllegalStateException("DB down"));
        assertThatThrownBy(publisher::publishPending).isInstanceOf(IllegalStateException.class);
        verify(claims, never()).release(any());
    }

    @Test
    void isolatesBadDataAndContinues() {
        for (String payload : java.util.List.of("broken", "null", "{}", "{\"roomId\":10,\"participantIds\":[]}")) {
            var bad = row(10, "ROOM_CREATED", payload);
            var good = valid(20);
            given(claims.claim(any(), anyLong())).willReturn(Optional.of(bad), Optional.of(good), Optional.empty());
            given(claims.fail(eq(bad), anyString())).willReturn(true);
            publisher.publishPending();
            verify(claims).fail(eq(bad), anyString());
        }
        assertThat(meters.counter("chat.outbox.failed").count()).isEqualTo(4);
    }

    @Test
    void isolatesUnsupportedType() {
        var bad = row(10, "UNKNOWN", "{}");
        given(claims.claim(any(), anyLong())).willReturn(Optional.of(bad), Optional.empty());
        publisher.publishPending();
        verify(claims).fail(eq(bad), anyString());
        verifyNoInteractions(redis);
    }

    @Test
    void doesNotCountAlreadyCompletedOrReclaimedRow() {
        var row = valid(10);
        given(claims.claim(any(), anyLong())).willReturn(Optional.of(row), Optional.empty());
        given(claims.complete(row)).willReturn(false);
        publisher.publishPending();
        assertThat(meters.counter(OutboxPublisher.RECOVERED_METRIC).count()).isZero();
    }

    @Test
    void noopWhenEmpty() {
        given(claims.claim(any(), anyLong())).willReturn(Optional.empty());
        publisher.publishPending();
        verifyNoInteractions(redis);
    }

    @Test
    void cleanupDeletesOld() {
        publisher.cleanup();
        verify(repository).deletePublishedBefore(any());
    }
}
