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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("RoomSubscriptionRecovery - 재구독 시 DB 기준 방 구독 복구")
class RoomSubscriptionRecoveryTest {

    private final WebSocketSessionStore store = new WebSocketSessionStore();
    private final ChatRoomUseCase chatRoomUseCase = mock(ChatRoomUseCase.class);
    private final ChatBroadcaster broadcaster = mock(ChatBroadcaster.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final RoomSubscriptionRecovery recovery =
            new RoomSubscriptionRecovery(store, chatRoomUseCase, broadcaster, meterRegistry);

    @AfterEach
    void tearDown() {
        recovery.shutdown();
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

        int recovered = recovery.recover();

        assertThat(recovered).isEqualTo(1);
        assertThat(store.isSubscribed(20L, session)).isTrue();
        verify(broadcaster).sendSyncRequired(session, 20L);
        verify(broadcaster, never()).sendSyncRequired(session, 10L);
        assertThat(meterRegistry.counter(RoomSubscriptionRecovery.RECOVERED_METRIC).count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("같은 사용자의 여러 연결은 DB 를 한 번만 조회하고 모두 복구한다")
    void queriesOncePerUser() {
        WebSocketSession first = session("s-1", 1L, true);
        WebSocketSession second = session("s-2", 1L, true);
        given(chatRoomUseCase.getMyRoomIds(1L)).willReturn(List.of(20L));

        int recovered = recovery.recover();

        assertThat(recovered).isEqualTo(2);
        assertThat(store.isSubscribed(20L, first)).isTrue();
        assertThat(store.isSubscribed(20L, second)).isTrue();
        verify(chatRoomUseCase, times(1)).getMyRoomIds(1L);
    }

    @Test
    @DisplayName("닫힌 연결은 건너뛰고, 한 사용자 조회가 실패해도 다른 사용자는 복구한다")
    void skipsClosedAndContinuesAfterFailure() {
        session("closed", 1L, false);
        session("failing", 2L, true);
        WebSocketSession ok = session("ok", 3L, true);
        given(chatRoomUseCase.getMyRoomIds(2L)).willThrow(new RuntimeException("db down"));
        given(chatRoomUseCase.getMyRoomIds(3L)).willReturn(List.of(30L));

        int recovered = recovery.recover();

        assertThat(recovered).isEqualTo(1);
        assertThat(store.isSubscribed(30L, ok)).isTrue();
        verify(chatRoomUseCase, never()).getMyRoomIds(1L);
        verify(broadcaster, times(1)).sendSyncRequired(any(), any());
    }

    @Test
    @DisplayName("이미 모두 구독 중이면 아무것도 보내지 않는다")
    void noopWhenAlreadySubscribed() {
        WebSocketSession session = session("s-1", 1L, true);
        store.subscribe(10L, session);
        given(chatRoomUseCase.getMyRoomIds(1L)).willReturn(List.of(10L));

        assertThat(recovery.recover()).isZero();
        verify(broadcaster, never()).sendSyncRequired(any(), any());
    }
}
