package com.project.bangjjack.domain.chat.domain.service;

import com.project.bangjjack.domain.chat.domain.repository.ChatRoomParticipantRepository;
import com.project.bangjjack.domain.chat.domain.repository.ChatRoomRepository;
import com.project.bangjjack.global.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(showSql = false)
@Import({ChatRoomCreateService.class, QuerydslConfig.class,
        ChatLastMessageQueryTest.RepositoryImplementations.class})
@ActiveProfiles("test")
class ChatRoomSubscriptionTargetsTest {

    @Autowired ChatRoomCreateService creates;
    @Autowired ChatRoomRepository rooms;
    @Autowired EntityManager entityManager;
    @Autowired ChatRoomParticipantRepository participants;

    @Test
    @DisplayName("방 생성 후 나간 참여자와 삭제된 참여자·방은 현재 구독 대상에서 제외한다")
    void activeSubscriptionTargetsExcludeLeftAndDeleted() {
        Long roomId = creates.createDirectRoom(1L, 2L, "DM_1_2").getId();
        assertThat(participants.findActiveUserIdsByRoomId(roomId)).containsExactlyInAnyOrder(1L, 2L);
        var first = participants.findByChatRoomIdAndUserIdAndDeletedFalse(roomId, 1L).orElseThrow();
        first.leave(null);
        entityManager.flush();
        entityManager.clear();

        assertThat(participants.findActiveUserIdsByRoomId(roomId)).containsExactly(2L);
        first = participants.findByChatRoomIdAndUserIdAndDeletedFalse(roomId, 1L).orElseThrow();
        first.rejoin();
        entityManager.flush();
        assertThat(participants.findActiveUserIdsByRoomId(roomId)).containsExactlyInAnyOrder(1L, 2L);
        first.softDelete();
        entityManager.flush();
        assertThat(participants.findActiveUserIdsByRoomId(roomId)).containsExactly(2L);

        rooms.findById(roomId).orElseThrow().softDelete();
        entityManager.flush();
        entityManager.clear();
        assertThat(participants.findActiveUserIdsByRoomId(roomId)).isEmpty();
    }


}
