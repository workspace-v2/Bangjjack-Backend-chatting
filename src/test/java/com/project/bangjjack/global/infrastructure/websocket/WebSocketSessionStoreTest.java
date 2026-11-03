package com.project.bangjjack.global.infrastructure.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@DisplayName("WebSocketSessionStore")
class WebSocketSessionStoreTest {

    @Test
    @DisplayName("한 세션은 여러 방에 구독돼도 같은 전송 데코레이터를 쓴다 (동시 전송 충돌 방지)")
    void sameDecoratorAcrossRooms() {
        WebSocketSessionStore store = new WebSocketSessionStore();
        WebSocketSession raw = mock(WebSocketSession.class);
        given(raw.getId()).willReturn("s-1");
        given(raw.isOpen()).willReturn(true);

        store.registerGlobal(raw);
        store.subscribe(1L, raw);
        store.subscribe(2L, raw);

        WebSocketSession inRoom1 = store.getSessionsByRoom(1L).iterator().next();
        WebSocketSession inRoom2 = store.getSessionsByRoom(2L).iterator().next();
        assertThat(inRoom1).isSameAs(inRoom2).isSameAs(store.getById("s-1").orElseThrow());
        assertThat(store.getAllSessions()).hasSize(1);
    }
    @Test
    @DisplayName("정리 전 얻은 세션으로 뒤늦게 구독해도 전역 등록과 방 구독을 되살리지 않는다")
    void staleReferenceCannotResubscribeAfterCleanup() {
        WebSocketSessionStore store = new WebSocketSessionStore();
        WebSocketSession raw = mock(WebSocketSession.class);
        given(raw.getId()).willReturn("s-1");
        given(raw.isOpen()).willReturn(true);
        store.registerGlobal(raw);
        store.subscribe(1L, raw);
        WebSocketSession stale = store.getById("s-1").orElseThrow();

        store.deregisterGlobal(raw);
        // isOpen 이 아직 true 여도 전역 등록이 끝났다면 구독할 수 없다.
        store.subscribe(2L, stale);

        assertThat(store.getAllSessions()).isEmpty();
        assertThat(store.getSubscribedRoomIds()).isEmpty();
        assertThat(store.isSubscribed(1L, raw)).isFalse();
        assertThat(store.isSubscribed(2L, raw)).isFalse();
    }

    @Test
    @DisplayName("등록된 연결이 닫혔다면 새 방에 구독시키지 않는다")
    void closedRegisteredSessionCannotSubscribe() {
        WebSocketSessionStore store = new WebSocketSessionStore();
        WebSocketSession raw = mock(WebSocketSession.class);
        given(raw.getId()).willReturn("s-1");
        given(raw.isOpen()).willReturn(true);
        store.registerGlobal(raw);
        given(raw.isOpen()).willReturn(false);

        store.subscribe(1L, raw);

        assertThat(store.getSessionsByRoom(1L)).isEmpty();
        assertThat(store.isSubscribed(1L, raw)).isFalse();
    }

    @Test
    @DisplayName("전역 등록되지 않은 세션은 구독만으로 등록되지 않는다")
    void unregisteredSessionCannotSubscribe() {
        WebSocketSessionStore store = new WebSocketSessionStore();
        WebSocketSession raw = mock(WebSocketSession.class);
        given(raw.getId()).willReturn("s-1");
        given(raw.isOpen()).willReturn(true);

        store.subscribe(1L, raw);

        assertThat(store.getAllSessions()).isEmpty();
        assertThat(store.getSubscribedRoomIds()).isEmpty();
    }

    @Test
    @DisplayName("클라이언트가 해제한 방은 복구용 구독에서 건너뛰고, 다시 SUBSCRIBE 하면 기록이 지워진다")
    void clientUnsubscribeIsRespectedByRecovery() {
        WebSocketSessionStore store = new WebSocketSessionStore();
        WebSocketSession raw = mock(WebSocketSession.class);
        given(raw.getId()).willReturn("s-1");
        given(raw.isOpen()).willReturn(true);
        store.registerGlobal(raw);
        store.subscribe(1L, raw);

        store.unsubscribeByClient(1L, raw);

        assertThat(store.subscribeIfMissing(1L, raw)).isFalse();
        assertThat(store.isSubscribed(1L, raw)).isFalse();
        assertThat(store.subscribeIfMissing(2L, raw)).isTrue(); // 해제하지 않은 방은 복구

        store.subscribe(1L, raw); // 클라이언트가 다시 구독
        assertThat(store.isUnsubscribedByClient(1L, raw)).isFalse();
        assertThat(store.isSubscribed(1L, raw)).isTrue();
    }

    @Test
    @DisplayName("연결 정리 시 클라이언트 해제 기록도 지운다")
    void deregisterClearsClientUnsubscribed() {
        WebSocketSessionStore store = new WebSocketSessionStore();
        WebSocketSession raw = mock(WebSocketSession.class);
        given(raw.getId()).willReturn("s-1");
        given(raw.isOpen()).willReturn(true);
        store.registerGlobal(raw);
        store.unsubscribeByClient(1L, raw);

        store.deregisterGlobal(raw);

        assertThat(store.isUnsubscribedByClient(1L, raw)).isFalse();
    }
}
