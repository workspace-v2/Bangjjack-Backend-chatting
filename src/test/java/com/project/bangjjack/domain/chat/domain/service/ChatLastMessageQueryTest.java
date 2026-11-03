package com.project.bangjjack.domain.chat.domain.service;

import com.project.bangjjack.domain.chat.domain.entity.Chat;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoom;
import com.project.bangjjack.domain.chat.domain.entity.MessageType;
import com.project.bangjjack.domain.chat.domain.repository.ChatRepository;
import com.project.bangjjack.domain.chat.domain.repository.ChatRoomRepository;
import com.project.bangjjack.domain.chat.infrastructure.repository.ChatRoomParticipantRepositoryImpl;
import com.project.bangjjack.domain.post.infrastructure.repository.RoommatePostRepositoryImpl;
import com.project.bangjjack.global.config.QuerydslConfig;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 채팅방 목록의 마지막 메시지: 저장 시 chat_room.last_message_id 갱신 → 목록 조회가 그 id 로 메시지를 찾는다 (H2). */
@DataJpaTest(showSql = false)
@Import({ChatSaveService.class, ChatStateUpdateService.class, ChatRoomCreateService.class, QuerydslConfig.class,
        ChatLastMessageQueryTest.RepositoryImplementations.class})
@ActiveProfiles("test")
@DisplayName("채팅방 마지막 메시지 조회 (last_message_id)")
class ChatLastMessageQueryTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class RepositoryImplementations {
        @Bean
        ChatRoomParticipantRepositoryImpl chatRoomParticipantRepositoryImpl(JPAQueryFactory queryFactory) {
            return new ChatRoomParticipantRepositoryImpl(queryFactory);
        }

        @Bean
        RoommatePostRepositoryImpl roommatePostRepositoryImpl(JPAQueryFactory queryFactory) {
            return new RoommatePostRepositoryImpl(queryFactory);
        }
    }

    @Autowired ChatSaveService saves;
    @Autowired ChatRoomCreateService creates;
    @Autowired ChatRepository chats;
    @Autowired ChatRoomRepository rooms;
    @Autowired EntityManager entityManager;

    private Chat send(Long roomId, Long senderId, String content) {
        ChatRoom room = rooms.findById(roomId).orElseThrow();
        Chat saved = saves.save(senderId, room, content, MessageType.USER);
        entityManager.flush();
        entityManager.clear(); // 벌크 UPDATE 결과를 다시 읽도록
        return saved;
    }

    @Test
    @DisplayName("메시지를 저장할 때마다 방의 last_message_id 와 last_message_at 이 마지막 메시지로 갱신된다")
    void saveUpdatesLastMessage() {
        Long roomId = creates.createDirectRoom(1L, 2L, "DM_1_2").getId();
        send(roomId, 1L, "first");
        Chat last = send(roomId, 2L, "second");

        ChatRoom room = rooms.findById(roomId).orElseThrow();
        assertThat(room.getLastMessageId()).isEqualTo(last.getId());
        assertThat(room.getLastMessageAt()).isNotNull();
    }

    @Test
    @DisplayName("방마다 마지막 메시지만 돌려주고, 메시지가 없는 방은 빠진다")
    void findLastMessagesByRoomIds() {
        Long roomA = creates.createDirectRoom(1L, 2L, "DM_1_2").getId();
        Long roomB = creates.createDirectRoom(1L, 3L, "DM_1_3").getId();
        Long empty = creates.createDirectRoom(1L, 4L, "DM_1_4").getId();
        send(roomA, 1L, "a-1");
        Chat lastA = send(roomA, 2L, "a-2");
        Chat lastB = send(roomB, 3L, "b-1");

        List<Chat> result = chats.findLastMessagesByRoomIds(List.of(roomA, roomB, empty));

        assertThat(result).extracting(Chat::getId).containsExactlyInAnyOrder(lastA.getId(), lastB.getId());
        assertThat(result).extracting(Chat::getContent).containsExactlyInAnyOrder("a-2", "b-1");
    }
}
