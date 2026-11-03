package com.project.bangjjack.domain.chat.infrastructure.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.bangjjack.domain.chat.application.event.ChatRoomCreatedEvent;
import com.project.bangjjack.domain.chat.infrastructure.pubsub.PubSubPublishExecutor;
import com.project.bangjjack.global.infrastructure.JsonSerializer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("ChatRoomCreatedEventListener - 발행 실패 시 재시도")
class ChatRoomCreatedEventListenerTest {

    private static final ChatRoomCreatedEvent EVENT = new ChatRoomCreatedEvent(10L, List.of(1L, 2L));

    private final RedissonClient redisson = mock(RedissonClient.class);
    private final RTopic topic = mock(RTopic.class);
    private final PubSubPublishExecutor executor = mock(PubSubPublishExecutor.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    // 재시도 간격 5초, 재시도 기간 3분
    private final ChatRoomCreatedEventListener listener = new ChatRoomCreatedEventListener(
            redisson, new JsonSerializer(new ObjectMapper()), executor, scheduler, meterRegistry, 5000, 180_000);

    @BeforeEach
    void setUp() {
        given(redisson.getTopic(anyString(), eq(StringCodec.INSTANCE))).willReturn(topic);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return true;
        }).when(executor).execute(anyLong(), any(Runnable.class));
    }

    /** 예약된 재시도를 바로 실행한다. */
    private void runScheduled() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, org.mockito.Mockito.atLeastOnce()).schedule(captor.capture(), any(Instant.class));
        captor.getValue().run();
    }

    @Test
    @DisplayName("발행 성공 시 재시도를 예약하지 않고, 일반 이벤트 JSON 에 delayed 를 넣지 않는다")
    void publishesOnceWithoutDelayedField() {
        listener.handle(EVENT);

        ArgumentCaptor<Object> json = ArgumentCaptor.forClass(Object.class);
        verify(topic).publish(json.capture());
        assertThat((String) json.getValue()).doesNotContain("delayed");
        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("발행이 실패하면 재시도하고, 재발행 이벤트는 delayed 로 표시한다")
    void retriesAsDelayedAfterFailure() {
        given(topic.publish(any())).willThrow(new RuntimeException("redis down")).willReturn(1L);

        listener.handle(EVENT);
        runScheduled();

        ArgumentCaptor<Object> json = ArgumentCaptor.forClass(Object.class);
        verify(topic, times(2)).publish(json.capture());
        assertThat((String) json.getAllValues().get(1)).contains("\"delayed\":true");
    }

    @Test
    @DisplayName("다음 재시도가 재시도 기간을 넘으면 포기하고 메트릭을 올린다")
    void givesUpWhenRetryPeriodExceeded() {
        // 재시도 기간(3초)이 간격(5초)보다 짧으면 첫 실패에서 바로 포기
        ChatRoomCreatedEventListener shortPeriod = new ChatRoomCreatedEventListener(
                redisson, new JsonSerializer(new ObjectMapper()), executor, scheduler, meterRegistry, 5000, 3000);
        given(topic.publish(any())).willThrow(new RuntimeException("redis down"));

        shortPeriod.handle(EVENT);

        verify(topic, times(1)).publish(any());
        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
        assertThat(meterRegistry.counter("chat.pubsub.room-created.retry.exhausted").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("재시도 기간은 첫 실패 시각 기준이라, 재시도할 때마다 늘어나지 않는다")
    void retryDeadlineIsFixedAtFirstFailure() {
        given(topic.publish(any())).willThrow(new RuntimeException("redis down"));

        listener.handle(EVENT);
        Instant firstSchedule = captureLastSchedule();
        runScheduled();
        Instant secondSchedule = captureLastSchedule();

        // 두 번째 예약도 첫 실패 + 3분 안쪽
        assertThat(secondSchedule).isAfterOrEqualTo(firstSchedule);
        assertThat(secondSchedule).isBefore(firstSchedule.plusSeconds(180));
        verify(scheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }

    private Instant captureLastSchedule() {
        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(scheduler, org.mockito.Mockito.atLeastOnce()).schedule(any(Runnable.class), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("발행 대기열 초과로 버려져도 재시도를 예약하고, 다음 시도에서 발행한다")
    void retriesWhenRejectedByQueue() {
        doAnswer(inv -> false) // 첫 제출은 대기열 초과
                .doAnswer(inv -> {
                    ((Runnable) inv.getArgument(1)).run();
                    return true;
                })
                .when(executor).execute(anyLong(), any(Runnable.class));

        listener.handle(EVENT);
        verify(topic, never()).publish(any());
        runScheduled();

        ArgumentCaptor<Object> json = ArgumentCaptor.forClass(Object.class);
        verify(topic).publish(json.capture());
        assertThat((String) json.getValue()).contains("\"delayed\":true");
    }
}
