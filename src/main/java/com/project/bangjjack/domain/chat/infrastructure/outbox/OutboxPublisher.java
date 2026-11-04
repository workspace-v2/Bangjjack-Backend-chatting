package com.project.bangjjack.domain.chat.infrastructure.outbox;

import com.project.bangjjack.domain.chat.application.event.ChatRoomCreatedEvent;
import com.project.bangjjack.domain.chat.infrastructure.event.RoomCreatedPublisher;
import com.project.bangjjack.global.infrastructure.JsonSerializer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 안전망. 즉시 발행이 실패한(= Redis 장애) 방 생성 이벤트를 주기적으로 다시 발행한다.
 * Redis 가 복구되면 쌓인 이벤트가 모두 발행되므로, 장애가 아무리 길어도 유실되지 않는다.
 * 폴러가 발행하는 이벤트는 delayed 로 표시해 수신 서버가 새로 구독한 세션에 재동기화 신호를 보내게 한다.
 */
@Slf4j
@Component
public class OutboxPublisher {

    static final String RECOVERED_METRIC = "chat.outbox.recovered";

    private final OutboxEventRepository repository;
    private final RoomCreatedPublisher roomCreatedPublisher;
    private final JsonSerializer jsonSerializer;
    private final Counter recoveredCounter;
    private final Counter failedCounter;
    private final long graceMs;
    private final int batchSize;
    private final long retentionMinutes;

    public OutboxPublisher(
            OutboxEventRepository repository,
            RoomCreatedPublisher roomCreatedPublisher,
            JsonSerializer jsonSerializer,
            MeterRegistry meterRegistry,
            @Value("${chat.outbox.publish-grace-ms:3000}") long graceMs,
            @Value("${chat.outbox.batch-size:100}") int batchSize,
            @Value("${chat.outbox.retention-minutes:60}") long retentionMinutes
    ) {
        this.repository = repository;
        this.roomCreatedPublisher = roomCreatedPublisher;
        this.jsonSerializer = jsonSerializer;
        this.graceMs = graceMs;
        this.batchSize = batchSize;
        this.retentionMinutes = retentionMinutes;
        this.recoveredCounter = Counter.builder(RECOVERED_METRIC)
                .description("폴러가 재발행으로 복구한 방 생성 이벤트 수")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("chat.outbox.failed")
                .description("데이터 오류로 격리된 Outbox 이벤트 수")
                .register(meterRegistry);
        io.micrometer.core.instrument.Gauge.builder("chat.outbox.pending", repository,
                        OutboxEventRepository::countByPublishedAtIsNullAndFailedAtIsNull)
                .description("아직 발행되지 않은 Outbox 이벤트 수")
                .register(meterRegistry);
    }

    /**
     * 미발행 이벤트를 발행하고 표시한다. FOR UPDATE SKIP LOCKED 로 잠근 채 발행까지 한 트랜잭션에서 처리해,
     * 다중 서버·다중 폴링이 같은 행을 중복 발행하지 않게 한다. 발행이 실패하면(Redis 장애) 그 주기는 멈추고
     * 다음 주기에 다시 시도한다 (표시 안 된 행은 잠금이 풀려 다시 대상이 됨).
     * 데이터 오류는 실패 상태로 격리해 이후 정상 이벤트의 발행을 막지 않는다.
     */
    @Scheduled(scheduler = "outboxScheduler", fixedDelayString = "${chat.outbox.poll-interval-ms:1000}")
    @Transactional
    public void publishPending() {
        LocalDateTime threshold = LocalDateTime.now().minusNanos(graceMs * 1_000_000);
        List<OutboxEvent> pending = repository.findUnpublishedForUpdate(threshold, batchSize);
        if (pending.isEmpty()) {
            return;
        }
        int published = 0;
        for (OutboxEvent row : pending) {
            ChatRoomCreatedEvent event;
            try {
                event = decode(row);
            } catch (RuntimeException e) {
                row.markFailed(e.getMessage());
                failedCounter.increment();
                log.error("[Outbox] 데이터 오류로 이벤트 격리, 수정 후 재처리 필요 - id={}, roomId={}",
                        row.getId(), row.getRoomId(), e);
                continue;
            }
            try {
                roomCreatedPublisher.publish(event.asDelayed());
                row.markPublished();
                published++;
            } catch (Exception e) {
                // 대부분 Redis 장애. 나머지도 실패할 것이므로 이번 주기는 멈추고 표시된 것만 커밋한다.
                log.warn("[Outbox] 재발행 실패, 이번 주기 중단 - id={}, roomId={}, 원인={}",
                        row.getId(), row.getRoomId(), e.getMessage());
                break;
            }
        }
        if (published > 0) {
            recoveredCounter.increment(published);
            log.info("[Outbox] 재발행 완료 - count={}", published);
        }
    }

    private ChatRoomCreatedEvent decode(OutboxEvent row) {
        if (OutboxEvent.TYPE_ROOM_CREATED.equals(row.getEventType())) {
            ChatRoomCreatedEvent event = jsonSerializer.deserialize(row.getPayload(), ChatRoomCreatedEvent.class);
            if (event == null || event.roomId() == null || !event.roomId().equals(row.getRoomId())
                    || event.participantIds() == null || event.participantIds().isEmpty()
                    || event.participantIds().stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("유효하지 않은 방 생성 이벤트: " + row.getId());
            }
            return event;
        }
        throw new IllegalStateException("알 수 없는 Outbox 이벤트 타입: " + row.getEventType());
    }

    /** 발행 완료된 오래된 행을 정리한다 (테이블 무한 증가 방지). */
    @Scheduled(scheduler = "outboxScheduler", fixedDelayString = "${chat.outbox.cleanup-interval-ms:3600000}")
    @Transactional
    public void cleanup() {
        int deleted = repository.deletePublishedBefore(LocalDateTime.now().minusMinutes(retentionMinutes));
        if (deleted > 0) {
            log.info("[Outbox] 발행 완료 행 정리 - deleted={}", deleted);
        }
    }
}
