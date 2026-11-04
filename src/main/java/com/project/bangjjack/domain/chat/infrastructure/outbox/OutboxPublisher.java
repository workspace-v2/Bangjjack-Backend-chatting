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
import org.springframework.transaction.annotation.Propagation;

import java.time.LocalDateTime;

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
    private final OutboxClaimService claims;
    private final long leaseMs;
    private final RoomCreatedPublisher roomCreatedPublisher;
    private final JsonSerializer jsonSerializer;
    private final Counter recoveredCounter;
    private final Counter failedCounter;
    private final long graceMs;
    private final int batchSize;
    private final long retentionMinutes;

    public OutboxPublisher(
            OutboxEventRepository repository,
            OutboxClaimService claims,
            RoomCreatedPublisher roomCreatedPublisher,
            JsonSerializer jsonSerializer,
            MeterRegistry meterRegistry,
            @Value("${chat.outbox.publish-grace-ms:3000}") long graceMs,
            @Value("${chat.outbox.batch-size:100}") int batchSize,
            @Value("${chat.outbox.retention-minutes:60}") long retentionMinutes,
            @Value("${chat.outbox.claim-lease-ms:60000}") long leaseMs
    ) {
        if (leaseMs <= 0) {
            throw new IllegalArgumentException("claim-lease-ms는 양수여야 합니다.");
        }
        this.repository = repository;
        this.claims = claims;
        this.leaseMs = leaseMs;
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

    /** 한 행씩 선점·커밋한 뒤 Redis에 발행하고, 별도 트랜잭션에서 결과를 기록한다. */
    @Scheduled(scheduler = "outboxScheduler", fixedDelayString = "${chat.outbox.poll-interval-ms:1000}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void publishPending() {
        LocalDateTime threshold = LocalDateTime.now().minusNanos(graceMs * 1_000_000);
        int published = 0;
        for (int i = 0; i < batchSize; i++) {
            var next = claims.claim(threshold, leaseMs);
            if (next.isEmpty()) {
                break;
            }
            var row = next.get();
            ChatRoomCreatedEvent event;
            try {
                event = decode(row);
            } catch (RuntimeException e) {
                if (claims.fail(row, e.getMessage())) {
                    failedCounter.increment();
                }
                log.error("[Outbox] 데이터 오류로 이벤트 격리 - id={}, roomId={}", row.id(), row.roomId(), e);
                continue;
            }
            try {
                roomCreatedPublisher.publish(event.asDelayed());
            } catch (Exception e) {
                // 아직 시도하지 않은 행은 선점하지 않는다. 실패 행은 다음 주기에 다시 시도한다.
                claims.release(row);
                log.warn("[Outbox] 재발행 실패, 이번 주기 중단 - id={}, roomId={}", row.id(), row.roomId(), e);
                break;
            }
            // Redis 성공 후 DB 갱신 실패 시 선점을 유지한다. 만료 후 재발행될 수 있다(at-least-once).
            if (claims.complete(row)) {
                published++;
            }
        }
        if (published > 0) {
            recoveredCounter.increment(published);
            log.info("[Outbox] 재발행 완료 - count={}", published);
        }
    }

    private ChatRoomCreatedEvent decode(OutboxClaimService.Claim row) {
        if (OutboxEvent.TYPE_ROOM_CREATED.equals(row.eventType())) {
            ChatRoomCreatedEvent event = jsonSerializer.deserialize(row.payload(), ChatRoomCreatedEvent.class);
            if (event == null || event.roomId() == null || !event.roomId().equals(row.roomId())
                    || event.participantIds() == null || event.participantIds().isEmpty()
                    || event.participantIds().stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("유효하지 않은 방 생성 이벤트: " + row.id());
            }
            return event;
        }
        throw new IllegalStateException("알 수 없는 Outbox 이벤트 타입: " + row.eventType());
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
