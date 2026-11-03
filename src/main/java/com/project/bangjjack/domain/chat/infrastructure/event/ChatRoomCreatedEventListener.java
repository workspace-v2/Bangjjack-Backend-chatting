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
 * 다른 서버는 재구독 복구로도 알 수 없으므로(그 서버의 Redis 연결은 정상), 발행이 성공할 때까지 일정 간격으로 다시 발행한다.
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
    private final int maxRetries;
    private final Counter retryExhaustedCounter;

    public ChatRoomCreatedEventListener(
            RedissonClient redissonClient,
            JsonSerializer jsonSerializer,
            PubSubPublishExecutor publishExecutor,
            @Qualifier("taskScheduler") TaskScheduler taskScheduler,
            MeterRegistry meterRegistry,
            @Value("${chat.pubsub.room-created.retry-interval-ms:5000}") long retryIntervalMs,
            @Value("${chat.pubsub.room-created.max-retries:60}") int maxRetries
    ) {
        this.redissonClient = redissonClient;
        this.jsonSerializer = jsonSerializer;
        this.publishExecutor = publishExecutor;
        this.taskScheduler = taskScheduler;
        this.retryInterval = Duration.ofMillis(retryIntervalMs);
        this.maxRetries = maxRetries;
        this.retryExhaustedCounter = Counter.builder(RETRY_EXHAUSTED_METRIC)
                .description("재시도 한도를 넘어 발행하지 못한 채팅방 생성 이벤트 수")
                .register(meterRegistry);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(ChatRoomCreatedEvent event) {
        // DB 커넥션 반납 전에 Redis 를 기다리지 않도록 발행은 전용 실행기에서 한다.
        submit(event, 0);
    }

    private void submit(ChatRoomCreatedEvent event, int attempt) {
        publishExecutor.execute(event.roomId(), () -> publish(event, attempt));
    }

    private void publish(ChatRoomCreatedEvent event, int attempt) {
        try {
            String json = jsonSerializer.serialize(event);
            RTopic topic = redissonClient.getTopic(CHANNEL, StringCodec.INSTANCE);
            topic.publish(json);
            log.debug("[Redis Pub/Sub] 채팅방 생성 이벤트 발행 - roomId={}, attempt={}", event.roomId(), attempt);
        } catch (Exception e) {
            if (attempt >= maxRetries) {
                retryExhaustedCounter.increment();
                log.error("[Redis Pub/Sub] 채팅방 생성 이벤트 발행 포기 - roomId={}, attempts={}, 원인={}", event.roomId(), attempt + 1, e.getMessage());
                return;
            }
            log.warn("[Redis Pub/Sub] 채팅방 생성 이벤트 발행 실패, 재시도 예약 - roomId={}, attempt={}, 원인={}", event.roomId(), attempt, e.getMessage());
            taskScheduler.schedule(() -> submit(event.asDelayed(), attempt + 1), Instant.now().plus(retryInterval));
        }
    }
}
