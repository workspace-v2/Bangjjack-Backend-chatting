package com.project.bangjjack.domain.chat.infrastructure.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 미발행 이벤트를 id 순으로 잠그며 가져온다 (FOR UPDATE SKIP LOCKED).
     * 다중 서버의 폴러가 같은 행을 중복 처리하지 않게 하고, 잠긴 행은 건너뛴다.
     * 호출자의 트랜잭션 안에서 실행해야 잠금이 발행·표시까지 유지된다.
     * threshold: 이보다 먼저 생성된 행만 (즉시 발행이 표시할 유예 시간 확보).
     */
    @Query(value = "SELECT * FROM outbox_event WHERE published_at IS NULL AND created_at <= :threshold "
            + "ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> findUnpublishedForUpdate(@Param("threshold") LocalDateTime threshold, @Param("limit") int limit);

    /** 즉시 발행 성공 시 해당 방의 미발행 행을 발행됨으로 표시 (방은 한 번만 생성되므로 상관키로 roomId 사용). */
    @Transactional
    @Modifying
    @Query("UPDATE OutboxEvent o SET o.publishedAt = :now "
            + "WHERE o.roomId = :roomId AND o.eventType = com.project.bangjjack.domain.chat.infrastructure.outbox.OutboxEvent.TYPE_ROOM_CREATED "
            + "AND o.publishedAt IS NULL")
    void markRoomCreatedPublished(@Param("roomId") Long roomId, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("DELETE FROM OutboxEvent o WHERE o.publishedAt IS NOT NULL AND o.publishedAt < :before")
    int deletePublishedBefore(@Param("before") LocalDateTime before);

    long countByPublishedAtIsNull();
}
