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

    public void registerGlobal(WebSocketSession session) {
        activeSessions.computeIfAbsent(session.getId(), id -> decorate(session));
    }

    public void deregisterGlobal(WebSocketSession session) {
        activeSessions.remove(session.getId());
    }

    public Optional<WebSocketSession> getById(String sessionId) {
        return Optional.ofNullable(activeSessions.get(sessionId));
    }

    /** 이 서버의 모든 연결 (스냅샷). */
    public List<WebSocketSession> getAllSessions() {
        return List.copyOf(activeSessions.values());
    }

    public void subscribe(Long roomId, WebSocketSession session) {
        WebSocketSession decorated = activeSessions.computeIfAbsent(session.getId(), id -> decorate(session));
        roomSessions.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>()).put(session.getId(), decorated);
        sessionRooms.computeIfAbsent(session.getId(), k -> ConcurrentHashMap.newKeySet()).add(roomId);
    }

    public void unsubscribe(Long roomId, WebSocketSession session) {
        ConcurrentHashMap<String, WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions != null) {
            sessions.remove(session.getId());
            if (sessions.isEmpty()) roomSessions.remove(roomId);
        }
        Set<Long> rooms = sessionRooms.get(session.getId());
        if (rooms != null) rooms.remove(roomId);
    }

    public void removeAll(WebSocketSession session) {
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
