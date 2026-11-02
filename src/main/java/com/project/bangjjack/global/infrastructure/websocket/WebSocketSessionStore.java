package com.project.bangjjack.global.infrastructure.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSocketSessionStore {

    private static final int SEND_TIME_LIMIT_MS = 1_000;
    private static final int BUFFER_SIZE_LIMIT_BYTES = 64 * 1024;

    // roomId → (sessionId → thread-safe decorated session)
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, WebSocketSession>> roomSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<Long>> sessionRooms = new ConcurrentHashMap<>();
    // sessionId → raw session (for dynamic room subscription)
    private final ConcurrentHashMap<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();

    public void registerGlobal(WebSocketSession session) {
        activeSessions.put(session.getId(), session);
    }

    public void deregisterGlobal(WebSocketSession session) {
        activeSessions.remove(session.getId());
    }

    public Optional<WebSocketSession> getById(String sessionId) {
        return Optional.ofNullable(activeSessions.get(sessionId));
    }

    public void subscribe(Long roomId, WebSocketSession session) {
        WebSocketSession wrapped = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT_BYTES);
        roomSessions.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>()).put(session.getId(), wrapped);
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
}
