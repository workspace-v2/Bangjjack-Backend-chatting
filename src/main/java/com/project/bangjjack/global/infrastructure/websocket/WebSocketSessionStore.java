package com.project.bangjjack.global.infrastructure.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 이 서버에 연결된 WebSocket 세션 (로컬 메모리).
 *
 * 세션마다 thread-safe 데코레이터를 하나만 만들어 모든 전송(방 브로드캐스트, 재동기화 신호, ping)이 같은 데코레이터를 쓴다.
 * 방마다 데코레이터를 따로 만들면 같은 세션에 서로 다른 스레드가 동시에 쓰게 되어 전송이 깨질 수 있다.
 * 등록·구독·정리는 같은 모니터로 직렬화한다. 잠금 안에서는 로컬 상태만 변경하고 메시지는 전송하지 않는다.
 */
@Component
public class WebSocketSessionStore {

    private static final int SEND_TIME_LIMIT_MS = 1_000;
    private static final int BUFFER_SIZE_LIMIT_BYTES = 64 * 1024;

    // roomId → (sessionId → decorated session)
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, WebSocketSession>> roomSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<Long>> sessionRooms = new ConcurrentHashMap<>();
    // sessionId → decorated session (이 서버의 모든 연결)
    private final ConcurrentHashMap<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();
    // sessionId → 클라이언트가 UNSUBSCRIBE 로 직접 해제한 방 (구독 복구가 다시 등록하지 않도록)
    private final ConcurrentHashMap<String, Set<Long>> clientUnsubscribedRooms = new ConcurrentHashMap<>();

    public synchronized void registerGlobal(WebSocketSession session) {
        if (!session.isOpen()) return;
        activeSessions.computeIfAbsent(session.getId(), id -> decorate(session));
    }

    public synchronized void deregisterGlobal(WebSocketSession session) {
        activeSessions.remove(session.getId());
        clientUnsubscribedRooms.remove(session.getId());
        removeAll(session);
    }

    public Optional<WebSocketSession> getById(String sessionId) {
        return Optional.ofNullable(activeSessions.get(sessionId));
    }

    /** 이 서버의 모든 연결 (스냅샷). */
    public List<WebSocketSession> getAllSessions() {
        return List.copyOf(activeSessions.values());
    }

    public synchronized void subscribe(Long roomId, WebSocketSession session) {
        WebSocketSession decorated = activeSessions.get(session.getId());
        if (decorated == null || !decorated.isOpen()) return;
        roomSessions.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>()).put(session.getId(), decorated);
        sessionRooms.computeIfAbsent(session.getId(), k -> ConcurrentHashMap.newKeySet()).add(roomId);
        Set<Long> unsubscribed = clientUnsubscribedRooms.get(session.getId());
        if (unsubscribed != null) unsubscribed.remove(roomId);
    }

    /**
     * 클라이언트 UNSUBSCRIBE 요청. 구독을 해제하고, 구독 복구가 이 방을 다시 등록하지 않도록 기록한다.
     * 실제로 구독 중이던 방만 기록한다. 구독은 참여 검증(SUBSCRIBE)이나 DB 참여 목록으로만 생기므로
     * 기록 크기는 참여 방 수로 제한된다 (임의 roomId 를 반복 전송해 기록을 키우지 못함).
     */
    public synchronized void unsubscribeByClient(Long roomId, WebSocketSession session) {
        if (!activeSessions.containsKey(session.getId()) || !isSubscribed(roomId, session)) {
            return;
        }
        unsubscribe(roomId, session);
        clientUnsubscribedRooms.computeIfAbsent(session.getId(), k -> ConcurrentHashMap.newKeySet()).add(roomId);
    }

    /**
     * 구독 복구용. 이미 구독 중이거나 클라이언트가 직접 해제한 방이면 건너뛴다.
     * 확인과 등록을 같은 잠금 안에서 해, 그 사이 들어온 UNSUBSCRIBE 를 덮어쓰지 않는다.
     *
     * @return 새로 구독했으면 true
     */
    public synchronized boolean subscribeIfMissing(Long roomId, WebSocketSession session) {
        if (isSubscribed(roomId, session) || isUnsubscribedByClient(roomId, session)) return false;
        subscribe(roomId, session);
        return isSubscribed(roomId, session);
    }

    public boolean isUnsubscribedByClient(Long roomId, WebSocketSession session) {
        Set<Long> rooms = clientUnsubscribedRooms.get(session.getId());
        return rooms != null && rooms.contains(roomId);
    }

    public synchronized void unsubscribe(Long roomId, WebSocketSession session) {
        ConcurrentHashMap<String, WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions != null) {
            sessions.remove(session.getId());
            if (sessions.isEmpty()) roomSessions.remove(roomId);
        }
        Set<Long> rooms = sessionRooms.get(session.getId());
        if (rooms != null) rooms.remove(roomId);
    }

    private void removeAll(WebSocketSession session) {
        Set<Long> rooms = sessionRooms.remove(session.getId());
        if (rooms == null) return;
        rooms.forEach(roomId -> {
            ConcurrentHashMap<String, WebSocketSession> sessions = roomSessions.get(roomId);
            if (sessions != null) {
                sessions.remove(session.getId());
                if (sessions.isEmpty()) roomSessions.remove(roomId);
            }
        });
    }

    /** 이 서버에서 구독 중인 세션이 있는 방 목록 (스냅샷). */
    public Set<Long> getSubscribedRoomIds() {
        return Set.copyOf(roomSessions.keySet());
    }

    public Collection<WebSocketSession> getSessionsByRoom(Long roomId) {
        ConcurrentHashMap<String, WebSocketSession> sessions = roomSessions.get(roomId);
        return sessions != null ? sessions.values() : Collections.emptyList();
    }

    public boolean isSubscribed(Long roomId, WebSocketSession session) {
        Set<Long> rooms = sessionRooms.get(session.getId());
        return rooms != null && rooms.contains(roomId);
    }

    private WebSocketSession decorate(WebSocketSession session) {
        return session instanceof ConcurrentWebSocketSessionDecorator
                ? session
                : new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT_BYTES);
    }
}
