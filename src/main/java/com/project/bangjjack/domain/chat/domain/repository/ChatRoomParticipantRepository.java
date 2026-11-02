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

    @Modifying
    @Query("UPDATE ChatRoomParticipant p SET p.unreadCount = p.unreadCount + 1 WHERE p.chatRoom.id = :roomId AND p.userId != :senderId AND p.status = com.project.bangjjack.domain.chat.domain.entity.ParticipantStatus.ACTIVE AND p.deleted = false")
    void incrementUnreadCountExcludingSender(@Param("roomId") Long roomId, @Param("senderId") Long senderId);

    /**
     * 나간(LEFT) 참여자를 쓰기 락으로 조회한다 (SELECT ... FOR UPDATE, 현재 읽기).
     * 일반 SELECT 는 REPEATABLE READ 에서 트랜잭션의 첫 읽기 시점 스냅샷을 보므로, 채팅방 락을 기다린 뒤에도
     * 앞선 트랜잭션이 커밋한 재입장·unread 변경을 못 볼 수 있다. 락 조회는 항상 최신 커밋 값을 읽는다.
     * 반드시 채팅방 락(ChatRoomRepository.findByIdForUpdate) 이후에 호출해 락 순서(chat_room -> participant)를 지킨다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ChatRoomParticipant p WHERE p.chatRoom.id = :roomId AND p.status = com.project.bangjjack.domain.chat.domain.entity.ParticipantStatus.LEFT AND p.deleted = false")
    List<ChatRoomParticipant> findLeftByRoomIdForUpdate(@Param("roomId") Long roomId);
}
