package com.project.bangjjack.domain.chat.infrastructure.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Outbox 이벤트. 방 생성 트랜잭션 안에서 저장돼 방과 원자적으로 커밋되고, 발행(Redis)이 끝나면 published_at 이 채워진다.
 * 미발행 행은 폴러가 Redis 복구 때까지 재발행한다.
 */
@Entity
@Table(name = "outbox_event",
        indexes = @Index(name = "IDX_outbox_unpublished", columnList = "published_at, failed_at, id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    public static final String TYPE_ROOM_CREATED = "ROOM_CREATED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "room_id")
    private Long roomId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    public void markFailed(String reason) {
        this.failedAt = LocalDateTime.now();
        this.failureReason = reason;
    }

    private OutboxEvent(String eventType, Long roomId, String payload) {
        this.eventType = eventType;
        this.roomId = roomId;
        this.payload = payload;
        this.createdAt = LocalDateTime.now();
    }

    public static OutboxEvent roomCreated(Long roomId, String payload) {
        return new OutboxEvent(TYPE_ROOM_CREATED, roomId, payload);
    }

    public void markPublished() {
        this.publishedAt = LocalDateTime.now();
    }

    public boolean isPublished() {
        return publishedAt != null;
    }
}
