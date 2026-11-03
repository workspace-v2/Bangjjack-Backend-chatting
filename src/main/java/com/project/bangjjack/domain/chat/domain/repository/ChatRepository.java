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

    @Query("SELECT c FROM Chat c WHERE c.chatRoom.id = :roomId AND c.id < :cursorId AND c.deleted = false ORDER BY c.id DESC")
    List<Chat> findByCursorPage(@Param("roomId") Long roomId, @Param("cursorId") Long cursorId, Pageable pageable);

    @Query("SELECT c FROM Chat c WHERE c.chatRoom.id = :roomId AND c.id < :cursorId AND c.id > :visibleFromMessageId AND c.deleted = false ORDER BY c.id DESC")
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
