package com.project.bangjjack.domain.chat.infrastructure.pubsub;

import com.project.bangjjack.domain.chat.application.usecase.ChatRoomUseCase;
import com.project.bangjjack.domain.chat.infrastructure.broadcaster.ChatBroadcaster;
import com.project.bangjjack.global.infrastructure.websocket.WebSocketSessionStore;
import com.project.bangjjack.global.jwt.principal.MemberPrincipal;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis 연결이 끊긴 동안 놓친 방 생성 이벤트를 DB 기준으로 복구한다.
 *
 * 방 생성 이벤트는 Pub/Sub 으로만 전달되어, 끊긴 동안 생성된 방은 이 서버의 참여자 세션에 구독되지 않는다.
 * 방 생성 채널 재구독 시 로컬 세션의 참여 방을 DB 에서 다시 읽어 빠진 구독을 채우고,
 * 새로 구독한 방에는 재동기화 신호(SYNC_REQUIRED)를 보내 끊긴 동안 쌓인 메시지를 동기화 API 로 받게 한다.
 * - 클라이언트가 UNSUBSCRIBE 로 직접 해제한 방은 다시 등록하지 않는다 (이벤트 유실로 빠진 구독만 채운다).
 * - 참여 방 조회가 실패한 사용자는 일정 간격으로 제한된 횟수만큼 다시 복구한다 (재구독은 다시 일어나지 않으므로).
 * Redisson 이벤트 스레드에서 DB 를 조회하지 않도록 전용 스레드에서 실행하고, 대기 중인 복구가 있으면 요청을 합친다.
 */
@Slf4j
@Component
public class RoomSubscriptionRecovery {

    static final String RECOVERED_METRIC = "chat.ws.room-subscription.recovered";
    static final String FAILED_METRIC = "chat.ws.room-subscription.recovery.failed";

    private final WebSocketSessionStore sessionStore;
    private final ChatRoomUseCase chatRoomUseCase;
    private final ChatBroadcaster chatBroadcaster;
    private final Counter recoveredCounter;
    private final Counter failedCounter;
    private final long retryDelayMs;
    private final int maxRetries;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "room-subscription-recovery");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean pending = new AtomicBoolean(false);

    @Autowired
    public RoomSubscriptionRecovery(
            WebSocketSessionStore sessionStore,
            ChatRoomUseCase chatRoomUseCase,
            ChatBroadcaster chatBroadcaster,
            MeterRegistry meterRegistry,
            @Value("${chat.ws.room-subscription.recovery-retry-delay-ms:5000}") long retryDelayMs,
            @Value("${chat.ws.room-subscription.recovery-max-retries:5}") int maxRetries
    ) {
        this.sessionStore = sessionStore;
        this.chatRoomUseCase = chatRoomUseCase;
        this.chatBroadcaster = chatBroadcaster;
        this.retryDelayMs = retryDelayMs;
        this.maxRetries = maxRetries;
        this.recoveredCounter = Counter.builder(RECOVERED_METRIC)
                .description("Redis 재구독 시 DB 기준으로 복구한 세션별 방 구독 수")
                .register(meterRegistry);
        this.failedCounter = Counter.builder(FAILED_METRIC)
                .description("재시도 한도를 넘어 방 구독을 복구하지 못한 사용자 수")
                .register(meterRegistry);
    }

    /** 이 서버의 모든 로컬 세션에 대해 복구를 비동기로 요청한다. 아직 시작하지 않은 요청이 있으면 합친다. */
    public void requestRecovery() {
        if (pending.compareAndSet(false, true)) {
            executor.execute(() -> {
                pending.set(false);
                run(null, 0);
            });
        }
    }

    /** targetUserIds 가 null 이면 모든 로컬 사용자. 조회 실패 사용자는 제한된 횟수만큼 다시 예약한다. */
    private void run(Set<Long> targetUserIds, int attempt) {
        Result result;
        try {
            result = recover(targetUserIds);
        } catch (Exception e) {
            log.error("[WS] 방 구독 복구 실패 - 원인={}", e.getMessage(), e);
            return;
        }
        if (result.failedUserIds().isEmpty()) {
            return;
        }
        if (attempt >= maxRetries) {
            failedCounter.increment(result.failedUserIds().size());
            log.error("[WS] 방 구독 복구 포기 - users={}, attempts={}", result.failedUserIds(), attempt + 1);
            return;
        }
        log.warn("[WS] 방 구독 복구 재시도 예약 - users={}, attempt={}", result.failedUserIds().size(), attempt);
        executor.schedule(() -> run(result.failedUserIds(), attempt + 1), retryDelayMs, TimeUnit.MILLISECONDS);
    }

    record Result(int recovered, Set<Long> failedUserIds) {
    }

    /** 한 번 복구한다. targetUserIds 가 null 이면 모든 로컬 사용자. */
    Result recover(Set<Long> targetUserIds) {
        Map<Long, List<WebSocketSession>> sessionsByUser = new HashMap<>();
        for (WebSocketSession session : sessionStore.getAllSessions()) {
            if (session.isOpen()
                    && session.getAttributes().get("principal") instanceof MemberPrincipal principal
                    && (targetUserIds == null || targetUserIds.contains(principal.getMemberId()))) {
                sessionsByUser.computeIfAbsent(principal.getMemberId(), k -> new ArrayList<>()).add(session);
            }
        }

        int recovered = 0;
        Set<Long> failed = new HashSet<>();
        for (Map.Entry<Long, List<WebSocketSession>> entry : sessionsByUser.entrySet()) {
            List<Long> roomIds;
            try {
                roomIds = chatRoomUseCase.getMyRoomIds(entry.getKey());
            } catch (Exception e) {
                failed.add(entry.getKey());
                log.warn("[WS] 참여 방 조회 실패 - userId={}, 원인={}", entry.getKey(), e.getMessage());
                continue;
            }
            for (WebSocketSession session : entry.getValue()) {
                for (Long roomId : roomIds) {
                    if (!sessionStore.subscribeIfMissing(roomId, session)) {
                        continue; // 이미 구독 중, 클라이언트가 해제한 방, 또는 그 사이 연결 종료
                    }
                    recovered++;
                    if (!chatBroadcaster.sendSyncRequired(session, roomId)) {
                        break; // 신호 전송 실패로 연결이 닫힘 → 재연결 시 초기 구독·동기화로 복구
                    }
                    log.debug("[WS] 놓친 방 구독 복구 - userId={}, roomId={}, sessionId={}", entry.getKey(), roomId, session.getId());
                }
            }
        }
        recoveredCounter.increment(recovered);
        log.info("[WS] 방 구독 복구 - users={}, recovered={}, failed={}", sessionsByUser.size(), recovered, failed.size());
        return new Result(recovered, failed);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
