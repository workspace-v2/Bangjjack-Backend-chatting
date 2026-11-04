package com.project.bangjjack.domain.chat.infrastructure.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /** 짧은 선점 트랜잭션에서만 호출한다. 만료된 선점은 다른 폴러가 회수할 수 있다. */
    @Query(value = "SELECT * FROM outbox_event WHERE published_at IS NULL AND failed_at IS NULL "
            + "AND created_at <= :threshold AND (claimed_until IS NULL OR claimed_until <= :now) "
            + "ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> findClaimableForUpdate(@Param("threshold") LocalDateTime threshold,
                                           @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE OutboxEvent o SET o.publishedAt = :now, o.claimToken = NULL, o.claimedUntil = NULL "
            + "WHERE o.id = :id AND o.claimToken = :token AND o.publishedAt IS NULL AND o.failedAt IS NULL")
    int completeClaim(@Param("id") Long id, @Param("token") String token, @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE OutboxEvent o SET o.failedAt = :now, o.failureReason = :reason, "
            + "o.claimToken = NULL, o.claimedUntil = NULL "
            + "WHERE o.id = :id AND o.claimToken = :token AND o.publishedAt IS NULL AND o.failedAt IS NULL")
    int failClaim(@Param("id") Long id, @Param("token") String token,
                  @Param("now") LocalDateTime now, @Param("reason") String reason);

    @Modifying
    @Query("UPDATE OutboxEvent o SET o.claimToken = NULL, o.claimedUntil = NULL "
            + "WHERE o.id = :id AND o.claimToken = :token AND o.publishedAt IS NULL AND o.failedAt IS NULL")
    int releaseClaim(@Param("id") Long id, @Param("token") String token);

    /** 즉시 발행 성공 시 해당 방의 미발행 행을 발행됨으로 표시 (방은 한 번만 생성되므로 상관키로 roomId 사용). */
    @Transactional
    @Modifying
    @Query("UPDATE OutboxEvent o SET o.publishedAt = :now, o.claimToken = NULL, o.claimedUntil = NULL "
            + "WHERE o.roomId = :roomId AND o.eventType = com.project.bangjjack.domain.chat.infrastructure.outbox.OutboxEvent.TYPE_ROOM_CREATED "
            + "AND o.publishedAt IS NULL AND o.failedAt IS NULL")
    void markRoomCreatedPublished(@Param("roomId") Long roomId, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("DELETE FROM OutboxEvent o WHERE o.publishedAt IS NOT NULL AND o.publishedAt < :before")
    int deletePublishedBefore(@Param("before") LocalDateTime before);

    long countByPublishedAtIsNullAndFailedAtIsNull();
}
