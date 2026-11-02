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

    @Query("SELECT c FROM Chat c WHERE c.deleted = false AND c.id IN (SELECT MAX(c2.id) FROM Chat c2 WHERE c2.chatRoom.id IN :roomIds AND c2.deleted = false GROUP BY c2.chatRoom.id)")
    List<Chat> findLastMessagesByRoomIds(@Param("roomIds") Collection<Long> roomIds);

}
