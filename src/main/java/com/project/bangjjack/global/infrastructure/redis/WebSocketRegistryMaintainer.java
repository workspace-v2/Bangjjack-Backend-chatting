package com.project.bangjjack.global.infrastructure.redis;

import com.project.bangjjack.global.infrastructure.websocket.WebSocketSessionStore;
import com.project.bangjjack.global.jwt.principal.MemberPrincipal;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

/**
 * 세션 레지스트리 유지 작업.
 * - heartbeat: 이 서버가 살아 있음을 알리고 레지스트리를 로컬 세션과 맞춤 (기본 10초, TTL 30초)
 * - sweep: heartbeat 가 만료된 서버(강제 종료된 태스크)의 세션을 정리 (기본 15초)
 * 태스크가 강제 종료되면 afterConnectionClosed 가 호출되지 않아 세션이 레지스트리에 남는다. sweep 이 그것을 지운다.
 */
@Slf4j
@Component
@Profile("!test")
public class WebSocketRegistryMaintainer {

    static final String ZOMBIE_CLEANED_METRIC = "chat.ws.registry.zombie.cleaned";
    static final String LOCAL_SESSIONS_METRIC = "chat.ws.sessions.local";
    static final String STALE_REMOVED_METRIC = "chat.ws.registry.stale.removed";

    private final WebSocketSessionRegistry registry;
    private final WebSocketSessionStore sessionStore;
    private final Counter zombieCleaned;
    private final Counter staleRemoved;

    public WebSocketRegistryMaintainer(WebSocketSessionRegistry registry, WebSocketSessionStore sessionStore,
                                       MeterRegistry meterRegistry) {
        this.registry = registry;
        this.sessionStore = sessionStore;
        this.zombieCleaned = Counter.builder(ZOMBIE_CLEANED_METRIC)
                .description("죽은 서버에 남아 있다가 정리된 WebSocket 세션 수")
                .register(meterRegistry);
        this.staleRemoved = Counter.builder(STALE_REMOVED_METRIC)
                .description("heartbeat 대조로 제거한, 로컬에 없는데 레지스트리에 남아 있던 이 서버의 세션 수")
                .register(meterRegistry);
        Gauge.builder(LOCAL_SESSIONS_METRIC, sessionStore, s -> s.getAllSessions().size())
                .description("이 서버에 연결된 WebSocket 세션 수")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${chat.ws.server-heartbeat-interval-ms:10000}")
    public void heartbeat() {
        try {
            int stale = registry.heartbeat(this::localSessions);
            if (stale > 0) {
                staleRemoved.increment(stale);
            }
        } catch (Exception e) {
            log.warn("[WS Registry] heartbeat 실패 - 원인={}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${chat.ws.zombie-sweep-interval-ms:15000}",
            initialDelayString = "${chat.ws.zombie-sweep-interval-ms:15000}")
    public void sweep() {
        try {
            int cleaned = registry.cleanupDeadServers();
            if (cleaned > 0) {
                zombieCleaned.increment(cleaned);
            }
        } catch (Exception e) {
            log.warn("[WS Registry] 좀비 세션 정리 실패 - 원인={}", e.getMessage());
        }
    }

    @PreDestroy
    public void markDown() {
        try {
            registry.markDown();
        } catch (Exception e) {
            log.warn("[WS Registry] 종료 표시 실패 - 원인={}", e.getMessage());
        }
    }

    private Map<String, Long> localSessions() {
        Map<String, Long> result = new HashMap<>();
        for (WebSocketSession session : sessionStore.getAllSessions()) {
            if (session.isOpen() && session.getAttributes().get("principal") instanceof MemberPrincipal principal) {
                result.put(session.getId(), principal.getMemberId());
            }
        }
        return result;
    }
}
