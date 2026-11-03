package com.project.bangjjack.domain.chat.domain.entity;

import com.project.bangjjack.global.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "chat",
    indexes = {
        @Index(name = "IDX_chat_room_id", columnList = "room_id, id"),
        @Index(name = "IDX_chat_room_deleted_id", columnList = "room_id, deleted, id")
    },
    uniqueConstraints = @UniqueConstraint(name = "UQ_chat_room_seq", columnNames = {"room_id", "seq"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Chat extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long senderId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    private ChatRoom chatRoom;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false)
    private MessageType messageType;

    @Column(name = "application_id")
    private Long applicationId;

    /**
     * 방 안에서 1부터 빈틈없이 증가하는 순번. 방 락 안에서 chat_room.last_seq 를 올려 발급한다.
     * 클라이언트는 받은 seq 가 건너뛰면(gap) 누락으로 보고 동기화 API 로 빠진 메시지를 가져온다.
     */
    @Column(nullable = false)
    private Long seq;

    public static Chat create(Long senderId, ChatRoom chatRoom, String content, MessageType messageType, Long seq) {
        return create(senderId, chatRoom, content, messageType, null, seq);
    }

    public static Chat create(Long senderId, ChatRoom chatRoom, String content, MessageType messageType,
                              Long applicationId, Long seq) {
        return new Chat(senderId, chatRoom, content, messageType, applicationId, seq);
    }
}
