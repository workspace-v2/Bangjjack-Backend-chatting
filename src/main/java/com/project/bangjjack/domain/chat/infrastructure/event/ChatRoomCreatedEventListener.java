package com.project.bangjjack.domain.chat.infrastructure.event;

import com.project.bangjjack.domain.chat.application.event.ChatRoomCreatedEvent;
import com.project.bangjjack.domain.chat.infrastructure.pubsub.PubSubPublishExecutor;
import com.project.bangjjack.global.infrastructure.JsonSerializer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.Instant;

/**
 * 채팅방 생성 이벤트를 Redis 로 발행한다.
 *
 * 이 이벤트를 놓치면 참여자는 재연결 전까지 새 방 메시지를 받지 못한다. 이 서버의 Redis 연결이 끊겨 발행에 실패하면
 * 다른 서버는 재구독 복구로도 알 수 없으므로(그 서버의 Redis 연결은 정상), 첫 실패부터 정해진 기간 동안 일정 간격으로 다시 발행한다.
 * 재발행 이벤트는 delayed 로 표시해 수신 서버가 새로 구독한 세션에 재동기화 신호를 보내게 한다.
 */
@Slf4j
@Component
public class ChatRoomCreatedEventListener {

    private static final String CHANNEL = "chat:system:room-created";
    static final String RETRY_EXHAUSTED_METRIC = "chat.pubsub.room-created.retry.exhausted";

    private final RedissonClient redissonClient;
    private final JsonSerializer jsonSerializer;
    private final PubSubPublishExecutor publishExecutor;
    private final TaskScheduler taskScheduler;
    private final Duration retryInterval;
    private final Duration retryFor;
    private final Counter retryExhaustedCounter;

    public ChatRoomCreatedEventListener(
            RedissonClient redissonClient,
            JsonSerializer jsonSerializer,
            PubSubPublishExecutor publishExecutor,
            @Qualifier("taskScheduler") TaskScheduler taskScheduler,
            MeterRegistry meterRegistry,
            @Value("${chat.pubsub.room-created.retry-interval-ms:5000}") long retryIntervalMs,
            // 측정한 Redis 최대 다운 시간(약 2.5분)보다 길게. 더 긴 장애는 재연결 시 DB 기준 초기 구독으로 복구한다
            @Value("${chat.pubsub.room-created.retry-for-ms:180000}") long retryForMs
    ) {
        this.redissonClient = redissonClient;
        this.jsonSerializer = jsonSerializer;
        this.publishExecutor = publishExecutor;
        this.taskScheduler = taskScheduler;
        this.retryInterval = Duration.ofMillis(retryIntervalMs);
        this.retryFor = Duration.ofMillis(retryForMs);
        this.retryExhaustedCounter = Counter.builder(RETRY_EXHAUSTED_METRIC)
                .description("재시도 기간 안에 발행하지 못한 채팅방 생성 이벤트 수")
                .register(meterRegistry);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(ChatRoomCreatedEvent event) {
        // DB 커넥션 반납 전에 Redis 를 기다리지 않도록 발행은 전용 실행기에서 한다.
        submit(event, 0, null);
    }

    /** @param giveUpAt 이 시각이 지나면 재시도하지 않는다. 첫 실패 때 정한다 (null = 아직 실패 없음) */
    private void submit(ChatRoomCreatedEvent event, int attempt, Instant giveUpAt) {
        boolean accepted = publishExecutor.execute(event.roomId(), () -> publish(event, attempt, giveUpAt));
        if (!accepted) {
            // 대기열 초과로 버려지면 publish 가 실행되지 않아 재시도도 예약되지 않으므로 여기서 예약한다
            scheduleRetry(event, attempt, giveUpAt, "발행 대기열 초과");
        }
    }

    private void publish(ChatRoomCreatedEvent event, int attempt, Instant giveUpAt) {
        try {
            String json = jsonSerializer.serialize(event);
            RTopic topic = redissonClient.getTopic(CHANNEL, StringCodec.INSTANCE);
            topic.publish(json);
            log.debug("[Redis Pub/Sub] 채팅방 생성 이벤트 발행 - roomId={}, attempt={}", event.roomId(), attempt);
        } catch (Exception e) {
            scheduleRetry(event, attempt, giveUpAt, e.getMessage());
        }
    }

    private void scheduleRetry(ChatRoomCreatedEvent event, int attempt, Instant giveUpAt, String reason) {
        Instant now = Instant.now();
        Instant deadline = giveUpAt != null ? giveUpAt : now.plus(retryFor);
        Instant next = now.plus(retryInterval);
        if (next.isAfter(deadline)) {
            retryExhaustedCounter.increment();
            log.error("[Redis Pub/Sub] 채팅방 생성 이벤트 발행 포기 - roomId={}, attempts={}, 재시도 기간={}s, 원인={}",
                    event.roomId(), attempt + 1, retryFor.toSeconds(), reason);
            return;
        }
        log.warn("[Redis Pub/Sub] 채팅방 생성 이벤트 발행 실패, 재시도 예약 - roomId={}, attempt={}, 원인={}", event.roomId(), attempt, reason);
        taskScheduler.schedule(() -> submit(event.asDelayed(), attempt + 1, deadline), next);
    }
}
