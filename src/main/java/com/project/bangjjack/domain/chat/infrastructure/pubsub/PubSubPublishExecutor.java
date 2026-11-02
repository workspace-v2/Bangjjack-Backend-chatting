package com.project.bangjjack.domain.chat.infrastructure.pubsub;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Redis Pub/Sub 발행 전용 실행기.
 *
 * 커밋 후(AFTER_COMMIT) 리스너는 Spring 이 DB 커넥션을 반납하기 전에 같은 스레드에서 실행된다.
 * 그 안에서 Redis 에 직접 발행하면 Redis 장애 시 Redisson 재시도 동안 커넥션을 쥐고 기다려
 * 커넥션 풀이 고갈된다. 리스너는 발행 작업을 이 실행기에 넘기기만 하고 바로 반환한다.
 *
 * - 순서 보장: 키(채팅방 ID)별로 같은 단일 스레드 레인에서 실행해, 같은 방의 발행 순서를 유지한다.
 * - 메모리 보호: 레인마다 대기열 크기를 제한하고, 가득 차면 발행을 버리고 메트릭을 올린다.
 *   버려진 메시지는 DB 에 저장되어 있으므로 클라이언트 조회로 복구할 수 있다.
 */
@Slf4j
@Component
public class PubSubPublishExecutor implements DisposableBean {

    static final String REJECTED_METRIC = "chat.pubsub.publish.rejected";
    static final String QUEUED_METRIC = "chat.pubsub.publish.queued";

    private final ThreadPoolExecutor[] lanes;
    private final Counter rejectedCounter;

    public PubSubPublishExecutor(
            MeterRegistry meterRegistry,
            @Value("${chat.pubsub.publish.lanes:4}") int laneCount,
            @Value("${chat.pubsub.publish.queue-capacity:1000}") int queueCapacity
    ) {
        if (laneCount < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("lanes, queue-capacity 는 1 이상이어야 합니다.");
        }
        this.lanes = new ThreadPoolExecutor[laneCount];
        for (int i = 0; i < laneCount; i++) {
            String threadName = "pubsub-publish-" + i;
            lanes[i] = new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(queueCapacity),
                    runnable -> {
                        Thread thread = new Thread(runnable, threadName);
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy()
            );
        }
        this.rejectedCounter = Counter.builder(REJECTED_METRIC)
                .description("발행 대기열이 가득 차 버려진 Redis Pub/Sub 발행 수")
                .register(meterRegistry);
        Gauge.builder(QUEUED_METRIC, this, PubSubPublishExecutor::queuedTasks)
                .description("Redis Pub/Sub 발행 대기열에 쌓인 작업 수")
                .register(meterRegistry);
    }

    /**
     * 같은 key 의 작업은 제출 순서대로 실행된다. 호출 스레드는 기다리지 않는다.
     */
    public void execute(Long key, Runnable task) {
        ThreadPoolExecutor lane = lanes[Math.floorMod(Objects.hashCode(key), lanes.length)];
        try {
            lane.execute(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    log.error("[Redis Pub/Sub] 발행 작업 실패 - key={}", key, e);
                }
            });
        } catch (RejectedExecutionException e) {
            rejectedCounter.increment();
            log.error("[Redis Pub/Sub] 발행 대기열 초과로 발행을 버림 - key={}", key);
        }
    }

    int queuedTasks() {
        int total = 0;
        for (ThreadPoolExecutor lane : lanes) {
            total += lane.getQueue().size();
        }
        return total;
    }

    @Override
    public void destroy() throws InterruptedException {
        for (ThreadPoolExecutor lane : lanes) {
            lane.shutdown();
        }
        for (ThreadPoolExecutor lane : lanes) {
            if (!lane.awaitTermination(5, TimeUnit.SECONDS)) {
                lane.shutdownNow();
            }
        }
    }
}
