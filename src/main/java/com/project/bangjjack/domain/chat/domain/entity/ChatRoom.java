package com.project.bangjjack.domain.chat.domain.entity;

import com.project.bangjjack.global.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "chat_room",
    uniqueConstraints = @UniqueConstraint(name = "UQ_chat_room_direct_key", columnNames = "direct_room_key")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ChatRoom extends BaseEntity {

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", nullable = false, length = 10)
    private RoomType roomType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RoomStatus status;

    @Column(name = "direct_room_key", length = 50)
    private String directRoomKey;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private ChatRoomCategory category;

    /** 이 방에서 마지막으로 발급한 메시지 seq. 메시지 저장 시 방 락 안에서만 증가한다. */
    @Column(name = "last_seq", nullable = false)
    private long lastSeq;

    /**
     * 이 방의 마지막 메시지 id. 메시지 저장 시 방 락 안에서 lastMessageAt 과 함께 갱신한다.
     * 채팅방 목록이 방마다 마지막 메시지를 PK 로 바로 찾는 데 쓴다 (메시지 수와 무관한 조회 비용).
     */
    @Column(name = "last_message_id")
    private Long lastMessageId;

    public static ChatRoom createDirect(Long createdBy, String directRoomKey) {
        return new ChatRoom(createdBy, RoomType.DIRECT, RoomStatus.OPEN, directRoomKey, null, ChatRoomCategory.GENERAL, 0L, null);
    }

    public static String generateDirectKey(Long userId1, Long userId2) {
        return "DM_" + Math.min(userId1, userId2) + "_" + Math.max(userId1, userId2);
    }

    public void updateLastMessageAt(LocalDateTime sentAt) {
        this.lastMessageAt = sentAt;
    }

    public void updateCategory(ChatRoomCategory newCategory) {
        if (newCategory == null) {
            throw new IllegalArgumentException("category는 null일 수 없습니다.");
        }
        this.category = newCategory;
    }

    public void close() {
        this.status = RoomStatus.CLOSED;
    }

    public void reopen() {
        this.status = RoomStatus.OPEN;
    }
}
