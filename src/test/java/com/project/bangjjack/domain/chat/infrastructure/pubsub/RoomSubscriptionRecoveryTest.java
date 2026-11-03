package com.project.bangjjack.domain.chat.infrastructure.pubsub;

import com.project.bangjjack.domain.chat.application.usecase.ChatRoomUseCase;
import com.project.bangjjack.domain.chat.infrastructure.broadcaster.ChatBroadcaster;
import com.project.bangjjack.global.infrastructure.websocket.WebSocketSessionStore;
import com.project.bangjjack.global.jwt.principal.MemberPrincipal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("RoomSubscriptionRecovery - 재구독 시 DB 기준 방 구독 복구")
class RoomSubscriptionRecoveryTest {

    private final WebSocketSessionStore store = new WebSocketSessionStore();
    private final ChatRoomUseCase chatRoomUseCase = mock(ChatRoomUseCase.class);
    private final ChatBroadcaster broadcaster = mock(ChatBroadcaster.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final RoomSubscriptionRecovery recovery =
            new RoomSubscriptionRecovery(store, chatRoomUseCase, broadcaster, meterRegistry, 50, 2);

    @AfterEach
    void tearDown() {
        recovery.shutdown();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 2000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
    }

    private WebSocketSession session(String id, Long userId, boolean open) {
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.getId()).willReturn(id);
        given(session.isOpen()).willReturn(open);
        MemberPrincipal principal = mock(MemberPrincipal.class);
        given(principal.getMemberId()).willReturn(userId);
        given(session.getAttributes()).willReturn(Map.of("principal", principal));
        store.registerGlobal(session);
        return session;
    }

    @Test
    @DisplayName("놓친 방만 구독하고 그 방에만 재동기화 신호를 보낸다")
    void subscribesMissingRoomsAndSendsSyncOnlyForThem() {
        WebSocketSession session = session("s-1", 1L, true);
        store.subscribe(10L, session);
        given(chatRoomUseCase.getMyRoomIds(1L)).willReturn(List.of(10L, 20L));

        RoomSubscriptionRecovery.Result result = recovery.recover(null);

        assertThat(result.recovered()).isEqualTo(1);
        assertThat(result.failedUserIds()).isEmpty();
        assertThat(store.isSubscribed(20L, session)).isTrue();
        // 저장소는 세션을 데코레이터로 감싸서 넘기므로 세션 ID 로 비교한다
        verify(broadcaster).sendSyncRequired(argThat(s -> s.getId().equals("s-1")), eq(20L));
        verify(broadcaster, never()).sendSyncRequired(any(), eq(10L));
        assertThat(meterRegistry.counter(RoomSubscriptionRecovery.RECOVERED_METRIC).count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("클라이언트가 UNSUBSCRIBE 로 해제한 방은 다시 구독하지 않는다")
    void doesNotResubscribeClientUnsubscribedRoom() {
        WebSocketSession session = session("s-1", 1L, true);
        store.subscribe(10L, session);
        store.unsubscribeByClient(10L, session);
        given(chatRoomUseCase.getMyRoomIds(1L)).willReturn(List.of(10L, 20L));

        RoomSubscriptionRecovery.Result result = recovery.recover(null);

        assertThat(result.recovered()).isEqualTo(1);
        assertThat(store.isSubscribed(10L, session)).isFalse();
        assertThat(store.isSubscribed(20L, session)).isTrue();
        verify(broadcaster, never()).sendSyncRequired(any(), eq(10L));
    }

    @Test
    @DisplayName("같은 사용자의 여러 연결은 DB 를 한 번만 조회하고 모두 복구한다")
    void queriesOncePerUser() {
        WebSocketSession first = session("s-1", 1L, true);
        WebSocketSession second = session("s-2", 1L, true);
        given(chatRoomUseCase.getMyRoomIds(1L)).willReturn(List.of(20L));

        assertThat(recovery.recover(null).recovered()).isEqualTo(2);
        assertThat(store.isSubscribed(20L, first)).isTrue();
        assertThat(store.isSubscribed(20L, second)).isTrue();
        verify(chatRoomUseCase, times(1)).getMyRoomIds(1L);
    }

    @Test
    @DisplayName("닫힌 연결은 건너뛰고, 조회 실패 사용자는 결과에 남기고 다른 사용자는 복구한다")
    void skipsClosedAndReportsFailedUsers() {
        session("closed", 1L, false);
        session("failing", 2L, true);
        WebSocketSession ok = session("ok", 3L, true);
        given(chatRoomUseCase.getMyRoomIds(2L)).willThrow(new RuntimeException("db down"));
        given(chatRoomUseCase.getMyRoomIds(3L)).willReturn(List.of(30L));

        RoomSubscriptionRecovery.Result result = recovery.recover(null);

        assertThat(result.recovered()).isEqualTo(1);
        assertThat(result.failedUserIds()).containsExactly(2L);
        assertThat(store.isSubscribed(30L, ok)).isTrue();
        verify(chatRoomUseCase, never()).getMyRoomIds(1L);
    }

    @Test
    @DisplayName("조회에 실패한 사용자만 다시 복구한다")
    void retriesOnlyFailedUsers() {
        WebSocketSession failing = session("failing", 2L, true);
        session("ok", 3L, true);
        given(chatRoomUseCase.getMyRoomIds(2L))
                .willThrow(new RuntimeException("db down"))
                .willReturn(List.of(20L));
        given(chatRoomUseCase.getMyRoomIds(3L)).willReturn(List.of(30L));

        recovery.requestRecovery();

        verify(broadcaster, timeout(2000)).sendSyncRequired(argThat(s -> s.getId().equals("failing")), eq(20L));
        assertThat(store.isSubscribed(20L, failing)).isTrue();
        verify(chatRoomUseCase, times(2)).getMyRoomIds(2L);
        verify(chatRoomUseCase, times(1)).getMyRoomIds(3L); // 성공한 사용자는 다시 조회하지 않음
    }

    @Test
    @DisplayName("재시도 한도를 넘으면 포기하고 메트릭을 올린다")
    void givesUpAfterMaxRetries() {
        session("failing", 2L, true);
        given(chatRoomUseCase.getMyRoomIds(2L)).willThrow(new RuntimeException("db down"));

        recovery.requestRecovery();

        // 최초 1회 + 재시도 2회
        verify(chatRoomUseCase, timeout(2000).times(3)).getMyRoomIds(2L);
        waitUntil(() -> meterRegistry.counter(RoomSubscriptionRecovery.FAILED_METRIC).count() == 1.0);
        assertThat(meterRegistry.counter(RoomSubscriptionRecovery.FAILED_METRIC).count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("이미 모두 구독 중이면 아무것도 보내지 않는다")
    void noopWhenAlreadySubscribed() {
        WebSocketSession session = session("s-1", 1L, true);
        store.subscribe(10L, session);
        given(chatRoomUseCase.getMyRoomIds(1L)).willReturn(List.of(10L));

        assertThat(recovery.recover(Set.of(1L)).recovered()).isZero();
        verify(broadcaster, never()).sendSyncRequired(any(), any());
    }
}
