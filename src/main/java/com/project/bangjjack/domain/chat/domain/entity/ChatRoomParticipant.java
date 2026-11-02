package com.project.bangjjack.domain.chat.domain.entity;

import com.project.bangjjack.global.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "chat_room_participant",
    uniqueConstraints = @UniqueConstraint(name = "UQ_participant_room_user", columnNames = {"room_id", "user_id"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ChatRoomParticipant extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    private ChatRoom chatRoom;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "last_read_message_id")
    private Long lastReadMessageId;

    @Column(name = "unread_count", nullable = false)
    private long unreadCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ParticipantStatus status;

    @Column(name = "visible_from_message_id")
    private Long visibleFromMessageId;

    public static ChatRoomParticipant create(ChatRoom chatRoom, Long userId) {
        return new ChatRoomParticipant(chatRoom, userId, null, 0, ParticipantStatus.ACTIVE, null);
    }

    public boolean markAsRead(Long messageId) {
        if (this.lastReadMessageId != null && messageId <= this.lastReadMessageId) {
            return false;
        }
        this.lastReadMessageId = messageId;
        this.unreadCount = 0;
        return true;
    }

    public void leave(Long latestMessageId) {
        this.status = ParticipantStatus.LEFT;
        this.visibleFromMessageId = latestMessageId;
    }

    public void rejoin() {
        this.status = ParticipantStatus.ACTIVE;
    }

    public boolean isLeft() {
        return this.status == ParticipantStatus.LEFT;
    }
}
