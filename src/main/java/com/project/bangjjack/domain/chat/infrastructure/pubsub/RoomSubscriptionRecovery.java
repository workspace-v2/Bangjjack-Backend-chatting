package com.project.bangjjack.domain.chat.infrastructure.pubsub;

import com.project.bangjjack.domain.chat.application.usecase.ChatRoomUseCase;
import com.project.bangjjack.domain.chat.infrastructure.broadcaster.ChatBroadcaster;
import com.project.bangjjack.global.infrastructure.websocket.WebSocketSessionStore;
import com.project.bangjjack.global.jwt.principal.MemberPrincipal;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis 연결이 끊긴 동안 놓친 방 생성 이벤트를 DB 기준으로 복구한다.
 *
 * 방 생성 이벤트는 Pub/Sub 으로만 전달되어, 끊긴 동안 생성된 방은 이 서버의 참여자 세션에 구독되지 않는다.
 * 방 생성 채널 재구독 시 로컬 세션의 참여 방을 DB 에서 다시 읽어 빠진 구독을 채우고,
 * 새로 구독한 방에는 재동기화 신호(SYNC_REQUIRED)를 보내 끊긴 동안 쌓인 메시지를 동기화 API 로 받게 한다.
 * Redisson 이벤트 스레드에서 DB 를 조회하지 않도록 전용 스레드에서 실행하고, 대기 중인 복구가 있으면 요청을 합친다.
 */
@Slf4j
@Component
public class RoomSubscriptionRecovery {

    static final String RECOVERED_METRIC = "chat.ws.room-subscription.recovered";

    private final WebSocketSessionStore sessionStore;
    private final ChatRoomUseCase chatRoomUseCase;
    private final ChatBroadcaster chatBroadcaster;
    private final Counter recoveredCounter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "room-subscription-recovery");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean pending = new AtomicBoolean(false);

    public RoomSubscriptionRecovery(WebSocketSessionStore sessionStore, ChatRoomUseCase chatRoomUseCase,
                                    ChatBroadcaster chatBroadcaster, MeterRegistry meterRegistry) {
        this.sessionStore = sessionStore;
        this.chatRoomUseCase = chatRoomUseCase;
        this.chatBroadcaster = chatBroadcaster;
        this.recoveredCounter = Counter.builder(RECOVERED_METRIC)
                .description("Redis 재구독 시 DB 기준으로 복구한 세션별 방 구독 수")
                .register(meterRegistry);
    }

    /** 복구를 비동기로 요청한다. 아직 시작하지 않은 요청이 있으면 합친다. */
    public void requestRecovery() {
        if (pending.compareAndSet(false, true)) {
            executor.execute(() -> {
                pending.set(false);
                try {
                    recover();
                } catch (Exception e) {
                    log.error("[WS] 방 구독 복구 실패 - 원인={}", e.getMessage(), e);
                }
            });
        }
    }

    /** @return 새로 구독한 (세션, 방) 수 */
    int recover() {
        Map<Long, List<WebSocketSession>> sessionsByUser = new HashMap<>();
        for (WebSocketSession session : sessionStore.getAllSessions()) {
            if (session.isOpen() && session.getAttributes().get("principal") instanceof MemberPrincipal principal) {
                sessionsByUser.computeIfAbsent(principal.getMemberId(), k -> new ArrayList<>()).add(session);
            }
        }

        int recovered = 0;
        for (Map.Entry<Long, List<WebSocketSession>> entry : sessionsByUser.entrySet()) {
            List<Long> roomIds;
            try {
                roomIds = chatRoomUseCase.getMyRoomIds(entry.getKey());
            } catch (Exception e) {
                log.warn("[WS] 참여 방 조회 실패로 구독 복구 건너뜀 - userId={}, 원인={}", entry.getKey(), e.getMessage());
                continue;
            }
            for (WebSocketSession session : entry.getValue()) {
                for (Long roomId : roomIds) {
                    if (sessionStore.isSubscribed(roomId, session)) continue;
                    sessionStore.subscribe(roomId, session);
                    if (!sessionStore.isSubscribed(roomId, session)) continue; // 그 사이 연결 종료
                    chatBroadcaster.sendSyncRequired(session, roomId);
                    recovered++;
                    log.debug("[WS] 놓친 방 구독 복구 - userId={}, roomId={}, sessionId={}", entry.getKey(), roomId, session.getId());
                }
            }
        }
        recoveredCounter.increment(recovered);
        log.info("[WS] 방 구독 복구 완료 - users={}, recovered={}", sessionsByUser.size(), recovered);
        return recovered;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
