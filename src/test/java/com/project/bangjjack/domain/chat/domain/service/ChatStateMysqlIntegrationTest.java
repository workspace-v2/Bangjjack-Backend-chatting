package com.project.bangjjack.domain.chat.domain.service;

import com.project.bangjjack.domain.chat.domain.entity.Chat;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoom;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoomCategory;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoomParticipant;
import com.project.bangjjack.domain.chat.domain.entity.ParticipantStatus;
import com.project.bangjjack.domain.chat.domain.entity.MessageType;
import com.project.bangjjack.domain.chat.domain.repository.ChatRepository;
import com.project.bangjjack.domain.chat.domain.repository.ChatRoomParticipantRepository;
import com.project.bangjjack.domain.chat.domain.repository.ChatRoomRepository;
import com.project.bangjjack.global.config.QuerydslConfig;
import com.project.bangjjack.domain.chat.infrastructure.repository.ChatRoomParticipantRepositoryImpl;
import com.project.bangjjack.domain.post.infrastructure.repository.RoommatePostRepositoryImpl;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Uses create-drop: CHAT_TEST_MYSQL_URL must point to a disposable, dedicated test database. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ChatSaveService.class, ChatStateUpdateService.class, ChatRoomCreateService.class, QuerydslConfig.class,
        ChatStateMysqlIntegrationTest.RepositoryImplementations.class})
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "CHAT_TEST_MYSQL_URL", matches = ".+")
@Timeout(30)
class ChatStateMysqlIntegrationTest {

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

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CHAT_TEST_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CHAT_TEST_MYSQL_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CHAT_TEST_MYSQL_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("spring.jpa.show-sql", () -> false);
    }

    @Autowired ChatSaveService saves;
    @Autowired ChatStateUpdateService updates;
    @Autowired ChatRoomCreateService creates;
    @Autowired ChatRepository chats;
    @Autowired ChatRoomRepository rooms;
    @Autowired ChatRoomParticipantRepository participants;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;

    TransactionTemplate transaction;
    Long roomId;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.executeWithoutResult(status -> {
            chats.deleteAllInBatch();
            participants.deleteAllInBatch();
            rooms.deleteAllInBatch();
            roomId = creates.createDirectRoom(1L, 2L, "DM_1_2").getId();
        });
    }

    @Test
    void concurrentSendsWithPreviouslyLoadedLeftParticipantKeepBothUnreadIncrements() throws Exception {
        updates.leave(roomId, 2L);
        CyclicBarrier snapshotsLoaded = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> sendFromOldSnapshot(snapshotsLoaded));
            var second = workers.submit(() -> sendFromOldSnapshot(snapshotsLoaded));
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }
        transaction.executeWithoutResult(status -> {
            ChatRoomParticipant recipient = participant(2L);
            assertThat(recipient.getStatus()).isEqualTo(ParticipantStatus.ACTIVE);
            assertThat(recipient.getUnreadCount()).isEqualTo(2);
            assertThat(chats.count()).isEqualTo(2);
        });
    }

    private void sendFromOldSnapshot(CyclicBarrier barrier) {
        transaction.executeWithoutResult(status -> {
            ChatRoom room = rooms.findById(roomId).orElseThrow();
            assertThat(participant(2L).isLeft()).isTrue();
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            saves.saveWithRejoin(1L, room, "동시 메시지");
        });
    }

    @Test
    void readFromOldSnapshotPreservesMessageCommittedAfterSnapshot() throws Exception {
        Long first = send();
        try (var worker = Executors.newSingleThreadExecutor()) {
            transaction.executeWithoutResult(status -> {
                assertThat(participant(2L).getUnreadCount()).isEqualTo(1);
                try {
                    worker.submit(this::send).get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                assertThat(updates.markAsRead(roomId, 2L, first)).isTrue();
                assertThat(participant(2L).getUnreadCount()).isEqualTo(1);
            });
        }
        transaction.executeWithoutResult(status -> assertThat(participant(2L).getUnreadCount()).isEqualTo(1));
    }

    @Test
    void olderReadCannotMoveCursorBackOrClearNewerUnread() {
        Long first = send();
        Long second = send();
        assertThat(updates.markAsRead(roomId, 2L, second)).isTrue();
        send();
        assertThat(updates.markAsRead(roomId, 2L, first)).isFalse();
        transaction.executeWithoutResult(status -> {
            assertThat(participant(2L).getLastReadMessageId()).isEqualTo(second);
            assertThat(participant(2L).getUnreadCount()).isEqualTo(1);
        });
    }

    @Test
    void foreignOrMissingMessageCannotAdvanceReadCursor() {
        Long first = send();
        Long foreign = transaction.execute(status -> {
            ChatRoom other = creates.createDirectRoom(3L, 4L, "DM_3_4");
            return saves.save(3L, other, "다른 방", MessageType.USER).getId();
        });
        assertThat(updates.markAsRead(roomId, 2L, foreign)).isFalse();
        assertThat(updates.markAsRead(roomId, 2L, Long.MAX_VALUE)).isFalse();
        assertThat(updates.markAsRead(roomId, 2L, first)).isTrue();
    }

    @Test
    void leaveUsesLatestCommittedMessageDespiteEarlierSnapshotAndRejoinRefreshesResponse() throws Exception {
        send();
        try (var worker = Executors.newSingleThreadExecutor()) {
            transaction.executeWithoutResult(status -> {
                participant(2L); // Establish a snapshot before the other transaction sends.
                Long latest;
                try {
                    latest = worker.submit(this::send).get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                updates.leave(roomId, 2L);
                assertThat(participant(2L).getVisibleFromMessageId()).isEqualTo(latest);
                assertThat(participant(2L).isLeft()).isTrue();
            });
        }
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                saves.saveWithRejoin(2L, rooms.findById(roomId).orElseThrow(), "퇴장 후 전송")))
                .isInstanceOf(com.project.bangjjack.domain.chat.application.exception.AlreadyLeftChatRoomException.class);
        var response = updates.rejoinAndGetParticipants(roomId, 2L);
        assertThat(response).allSatisfy(p -> assertThat(p.isLeft()).isFalse());
    }

    @Test
    void explicitCategoryUpdateDoesNotOverwriteTimestampFromConcurrentSend() throws Exception {
        try (var worker = Executors.newSingleThreadExecutor()) {
            transaction.executeWithoutResult(status -> {
                ChatRoom stale = rooms.findById(roomId).orElseThrow();
                assertThat(stale.getLastMessageAt()).isNull();
                try {
                    worker.submit(this::send).get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                updates.updateCategory(roomId, ChatRoomCategory.APPLICATION);
            });
        }
        transaction.executeWithoutResult(status -> {
            ChatRoom room = rooms.findById(roomId).orElseThrow();
            assertThat(room.getCategory()).isEqualTo(ChatRoomCategory.APPLICATION);
            assertThat(room.getLastMessageAt()).isNotNull();
        });
    }

    @Test
    void concurrentSendsGetGaplessSeqInCommitOrder() throws Exception {
        int senders = 8;
        int perSender = 5;
        CyclicBarrier start = new CyclicBarrier(senders);
        try (var workers = Executors.newFixedThreadPool(senders)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < senders; i++) {
                long senderId = i % 2 == 0 ? 1L : 2L;
                futures.add(workers.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    for (int n = 0; n < perSender; n++) {
                        transaction.executeWithoutResult(status ->
                                saves.saveWithRejoin(senderId, rooms.findById(roomId).orElseThrow(), "동시 메시지"));
                    }
                    return null;
                }));
            }
            for (var future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        }
        transaction.executeWithoutResult(status -> {
            var saved = chats.findAll().stream()
                    .sorted(java.util.Comparator.comparing(Chat::getId))
                    .toList();
            int total = senders * perSender;
            assertThat(saved).extracting(Chat::getSeq)
                    .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, total).boxed().toList());
            assertThat(rooms.findLastSeq(roomId)).contains((long) total);
        });
    }

    private Long send() {
        return transaction.execute(status -> {
            ChatRoom room = rooms.findById(roomId).orElseThrow();
            return saves.saveWithRejoin(1L, room, "메시지").getId();
        });
    }

    private ChatRoomParticipant participant(Long userId) {
        return participants.findByChatRoomIdAndUserIdAndDeletedFalse(roomId, userId).orElseThrow();
    }
}
