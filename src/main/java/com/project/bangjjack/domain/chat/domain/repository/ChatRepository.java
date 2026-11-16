package com.project.bangjjack.domain.chat.domain.repository;

import com.project.bangjjack.domain.chat.domain.entity.Chat;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChatRepository extends JpaRepository<Chat, Long> {

    /*
     * 메시지 페이지 쿼리는 인덱스 힌트(FORCE INDEX)를 쓰지 않는다.
     * ORDER BY id DESC LIMIT 이 있으면 옵티마이저(prefer_ordering_index)가 PRIMARY 역방향 스캔을 골라,
     * 방의 메시지가 id 범위에서 오래된 쪽에 몰려 있으면 다른 방 메시지를 대량으로 읽는다
     * (가장 큰 방 첫 페이지: 99,033행 읽음, 약 88ms). ANALYZE TABLE 로는 계획이 바뀌지 않았다.
     * 원인은 첫 페이지의 `id < Long.MAX_VALUE` 범위가 PRIMARY 비용을 낙관적으로 추정하게 만드는 것으로 보여,
     * 첫 페이지(cursor 없음)는 id 범위 조건이 없는 쿼리(findFirstPage)를 쓴다 (같은 방, 0.06ms).
     * 다음 페이지는 커서가 방 메시지 범위 안이라 PRIMARY 를 골라도 읽는 행이 적다.
     */
    @Query(value = "SELECT * FROM chat WHERE room_id = :roomId AND deleted = false ORDER BY id DESC", nativeQuery = true)
    List<Chat> findFirstPage(@Param("roomId") Long roomId, Pageable pageable);

    @Query(value = "SELECT * FROM chat "
            + "WHERE room_id = :roomId AND id < :cursorId AND deleted = false ORDER BY id DESC", nativeQuery = true)
    List<Chat> findByCursorPage(@Param("roomId") Long roomId, @Param("cursorId") Long cursorId, Pageable pageable);

    /* 재입장 이후 메시지만 (visibleFromMessageId 초과). 힌트 없이도 방 단위 인덱스를 고르는 것을 확인했다 */
    @Query(value = "SELECT * FROM chat "
            + "WHERE room_id = :roomId AND id < :cursorId AND id > :visibleFromMessageId AND deleted = false ORDER BY id DESC", nativeQuery = true)
    List<Chat> findByCursorPageAfterMessageId(@Param("roomId") Long roomId, @Param("cursorId") Long cursorId, @Param("visibleFromMessageId") Long visibleFromMessageId, Pageable pageable);

    /** 동기화: afterSeq 다음부터 untilSeq 까지, 재입장 이후(visibleFromMessageId 초과) 메시지를 seq 오름차순으로. */
    @Query("SELECT c FROM Chat c WHERE c.chatRoom.id = :roomId AND c.seq > :afterSeq AND c.seq <= :untilSeq AND c.id > :visibleFromMessageId AND c.deleted = false ORDER BY c.seq ASC")
    List<Chat> findSyncPage(@Param("roomId") Long roomId, @Param("afterSeq") long afterSeq, @Param("untilSeq") long untilSeq,
                            @Param("visibleFromMessageId") long visibleFromMessageId, Pageable pageable);

    @Query("SELECT MAX(c.id) FROM Chat c WHERE c.chatRoom.id = :roomId AND c.deleted = false")
    Optional<Long> findLatestMessageIdByRoomId(@Param("roomId") Long roomId);

    /**
     * 방마다 마지막 메시지. chat_room.last_message_id 로 PK 조인하므로 방 수만큼만 읽는다.
     * (이전의 id IN (SELECT MAX(id) ... GROUP BY room_id) 는 바깥 쿼리가 chat 전체를 스캔했다)
     */
    @Query("SELECT c FROM ChatRoom r JOIN Chat c ON c.id = r.lastMessageId WHERE r.id IN :roomIds AND c.deleted = false")
    List<Chat> findLastMessagesByRoomIds(@Param("roomIds") Collection<Long> roomIds);

}
