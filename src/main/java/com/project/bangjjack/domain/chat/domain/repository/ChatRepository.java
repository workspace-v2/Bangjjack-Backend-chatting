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
     * 메시지 페이지는 방 단위 인덱스를 강제한다 (네이티브 SQL, MySQL FORCE INDEX).
     * ORDER BY id DESC LIMIT 이 있으면 옵티마이저가 정렬 순서가 같은 PRIMARY 역방향 스캔을 골라
     * (prefer_ordering_index), 조건에 맞는 행을 찾을 때까지 다른 방 메시지까지 읽었다 (EXPLAIN rows 51,125, filtered 6%).
     * 인덱스 추가나 ORDER BY room_id 추가로는 선택이 바뀌지 않았고, prefer_ordering_index=off 는 UQ_chat_room_seq + filesort 로 더 나빴다.
     * FORCE INDEX (IDX_chat_room_deleted_id) 는 방·삭제 여부·id 순서로 필요한 행만 읽는다 (rows 1 추정, filesort 없음).
     * 페이지 크기는 Pageable 로 LIMIT 이 붙는다.
     */
    @Query(value = "SELECT * FROM chat FORCE INDEX (IDX_chat_room_deleted_id) "
            + "WHERE room_id = :roomId AND id < :cursorId AND deleted = false ORDER BY id DESC", nativeQuery = true)
    List<Chat> findByCursorPage(@Param("roomId") Long roomId, @Param("cursorId") Long cursorId, Pageable pageable);

    /* findByCursorPage 와 같은 이유로 방 단위 인덱스를 강제한다 (재입장 이후 메시지만) */
    @Query(value = "SELECT * FROM chat FORCE INDEX (IDX_chat_room_deleted_id) "
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
