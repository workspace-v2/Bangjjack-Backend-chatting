package com.project.bangjjack.domain.chat.domain.repository;

import com.project.bangjjack.domain.chat.domain.entity.ChatRoomParticipant;
import com.project.bangjjack.domain.chat.domain.entity.ParticipantStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatRoomParticipantRepository extends JpaRepository<ChatRoomParticipant, Long>, ChatRoomParticipantQueryRepository {

    List<ChatRoomParticipant> findByChatRoomIdAndStatusAndDeletedFalse(Long chatRoomId, ParticipantStatus status);

    List<ChatRoomParticipant> findByChatRoomIdAndDeletedFalse(Long chatRoomId);

    boolean existsByChatRoomIdAndUserIdAndStatusAndDeletedFalse(Long chatRoomId, Long userId, ParticipantStatus status);

    Optional<ChatRoomParticipant> findByChatRoomIdAndUserIdAndStatusAndDeletedFalse(Long chatRoomId, Long userId, ParticipantStatus status);

    Optional<ChatRoomParticipant> findByChatRoomIdAndUserIdAndDeletedFalse(Long chatRoomId, Long userId);

    @Query("SELECT p.chatRoom.id FROM ChatRoomParticipant p WHERE p.userId = :userId AND p.status = com.project.bangjjack.domain.chat.domain.entity.ParticipantStatus.ACTIVE AND p.deleted = false AND p.chatRoom.deleted = false")
    List<Long> findAllRoomIdsByUserId(@Param("userId") Long userId);

    @Query("SELECT p.lastReadMessageId FROM ChatRoomParticipant p WHERE p.chatRoom.id = :roomId AND p.userId != :userId AND p.deleted = false")
    Optional<Long> findPartnerLastReadMessageId(@Param("roomId") Long roomId, @Param("userId") Long userId);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE ChatRoomParticipant p SET p.unreadCount = p.unreadCount + 1, p.updatedAt = CURRENT_TIMESTAMP WHERE p.chatRoom.id = :roomId AND p.userId != :senderId AND p.status = com.project.bangjjack.domain.chat.domain.entity.ParticipantStatus.ACTIVE AND p.deleted = false")
    void incrementUnreadCountExcludingSender(@Param("roomId") Long roomId, @Param("senderId") Long senderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ChatRoomParticipant p WHERE p.chatRoom.id = :roomId AND p.userId = :userId AND p.deleted = false")
    Optional<ChatRoomParticipant> findParticipantForUpdate(@Param("roomId") Long roomId, @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ChatRoomParticipant p WHERE p.chatRoom.id = :roomId AND p.deleted = false")
    List<ChatRoomParticipant> findParticipantsForUpdate(@Param("roomId") Long roomId);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE ChatRoomParticipant p SET p.status = com.project.bangjjack.domain.chat.domain.entity.ParticipantStatus.ACTIVE, p.updatedAt = CURRENT_TIMESTAMP WHERE p.chatRoom.id = :roomId AND p.userId <> :senderId AND p.status = com.project.bangjjack.domain.chat.domain.entity.ParticipantStatus.LEFT AND p.deleted = false")
    int rejoinOthers(@Param("roomId") Long roomId, @Param("senderId") Long senderId);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE ChatRoomParticipant p SET p.status = com.project.bangjjack.domain.chat.domain.entity.ParticipantStatus.ACTIVE, p.updatedAt = CURRENT_TIMESTAMP WHERE p.chatRoom.id = :roomId AND p.userId = :userId AND p.status = com.project.bangjjack.domain.chat.domain.entity.ParticipantStatus.LEFT AND p.deleted = false")
    int rejoin(@Param("roomId") Long roomId, @Param("userId") Long userId);

    // DML의 현재 읽기를 사용해 앞선 일반 SELECT의 REPEATABLE READ 스냅샷에 의존하지 않는다.
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE chat_room_participant p
            SET status = 'LEFT', updated_at = CURRENT_TIMESTAMP,
                visible_from_message_id = (SELECT MAX(c.id) FROM chat c WHERE c.room_id = :roomId AND c.deleted = false)
            WHERE p.room_id = :roomId AND p.user_id = :userId AND p.status = 'ACTIVE' AND p.deleted = false
            """, nativeQuery = true)
    int leave(@Param("roomId") Long roomId, @Param("userId") Long userId);

    // 커서 이후 메시지는 unread로 남긴다. 오래된 READ는 커서를 후퇴시키거나 unread를 초기화하지 않는다.
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE chat_room_participant p
            SET last_read_message_id = :messageId, updated_at = CURRENT_TIMESTAMP,
                unread_count = (
                    SELECT COUNT(*) FROM chat c
                    WHERE c.room_id = :roomId AND c.user_id <> :userId AND c.deleted = false
                      AND c.id > :messageId
                      AND (p.visible_from_message_id IS NULL OR c.id > p.visible_from_message_id)
                )
            WHERE p.room_id = :roomId AND p.user_id = :userId AND p.status = 'ACTIVE' AND p.deleted = false
              AND (p.last_read_message_id IS NULL OR p.last_read_message_id < :messageId)
              AND EXISTS (SELECT 1 FROM chat target WHERE target.id = :messageId AND target.room_id = :roomId AND target.deleted = false)
            """, nativeQuery = true)
    int markAsRead(@Param("roomId") Long roomId, @Param("userId") Long userId, @Param("messageId") Long messageId);
}
