package com.project.bangjjack.domain.chat.infrastructure.pubsub;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PubSubPublishExecutor")
class PubSubPublishExecutorTest {

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private PubSubPublishExecutor executor;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (executor != null) {
            executor.destroy();
        }
    }

    @Test
    @DisplayName("같은 키(채팅방)의 작업은 제출 순서대로 실행된다")
    void sameKey_keepsOrder() throws InterruptedException {
        executor = new PubSubPublishExecutor(meterRegistry, 4, 1000);
        List<Integer> executed = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(200);

        for (int i = 0; i < 200; i++) {
            int seq = i;
            executor.execute(7L, () -> {
                executed.add(seq);
                done.countDown();
            });
        }

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(executed).containsExactlyElementsOf(IntStream.range(0, 200).boxed().toList());
    }

    @Test
    @DisplayName("발행이 막혀 있어도 호출 스레드는 기다리지 않는다 (DB 커넥션을 쥔 채 대기하지 않음)")
    void blockedPublish_doesNotBlockCaller() throws InterruptedException {
        executor = new PubSubPublishExecutor(meterRegistry, 1, 10);
        CountDownLatch redisDown = new CountDownLatch(1);

        long start = System.nanoTime();
        executor.execute(1L, () -> {
            try {
                redisDown.await(); // Redis 장애로 발행이 멈춘 상황
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(elapsedMs).isLessThan(100);
        redisDown.countDown();
    }

    @Test
    @DisplayName("대기열이 가득 차면 발행을 버리고 버림 메트릭을 올린다")
    void fullQueue_rejectsAndCounts() throws InterruptedException {
        executor = new PubSubPublishExecutor(meterRegistry, 1, 1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);

        executor.execute(1L, () -> { // 실행 중 (레인 점유)
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        executor.execute(1L, () -> { }); // 대기열 1칸 사용
        executor.execute(1L, () -> { }); // 초과 -> 버림

        assertThat(meterRegistry.get(PubSubPublishExecutor.REJECTED_METRIC).counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get(PubSubPublishExecutor.QUEUED_METRIC).gauge().value()).isEqualTo(1.0);
        release.countDown();
    }

    @Test
    @DisplayName("작업이 예외를 던져도 레인은 계속 다음 작업을 실행한다")
    void taskException_doesNotStopLane() throws InterruptedException {
        executor = new PubSubPublishExecutor(meterRegistry, 1, 10);
        CountDownLatch next = new CountDownLatch(1);

        executor.execute(1L, () -> {
            throw new IllegalStateException("publish failed");
        });
        executor.execute(1L, next::countDown);

        assertThat(next.await(1, TimeUnit.SECONDS)).isTrue();
    }
}
