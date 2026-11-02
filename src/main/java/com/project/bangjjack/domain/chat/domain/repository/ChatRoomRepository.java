package com.project.bangjjack.domain.chat.domain.repository;

import com.project.bangjjack.domain.chat.domain.entity.ChatRoom;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    @Query("SELECT cr FROM ChatRoom cr WHERE cr.directRoomKey = :directRoomKey AND cr.deleted = false")
    Optional<ChatRoom> findByDirectRoomKey(@Param("directRoomKey") String directRoomKey);

    @Query("SELECT cr FROM ChatRoom cr WHERE cr.id = :id AND cr.deleted = false")
    Optional<ChatRoom> findByIdAndDeletedFalse(@Param("id") Long id);

    boolean existsByIdAndDeletedFalse(Long id);

    /**
     * 채팅방 행에 쓰기 락(SELECT ... FOR UPDATE)을 건다.
     * 메시지 저장 트랜잭션은 이 락을 가장 먼저 잡아, 같은 방의 저장을 순서대로 처리하고 데드락을 막는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT cr FROM ChatRoom cr WHERE cr.id = :id")
    Optional<ChatRoom> findByIdForUpdate(@Param("id") Long id);
}
