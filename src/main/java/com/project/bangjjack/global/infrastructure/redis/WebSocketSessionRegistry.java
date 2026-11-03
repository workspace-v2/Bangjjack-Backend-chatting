package com.project.bangjjack.global.infrastructure.redis;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBatch;
import org.redisson.api.RLock;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 다중 서버 공용 WebSocket 세션 레지스트리 (Redis).
 *
 * 구조
 * - ws:sessions:{userId}           Set   사용자의 세션 ID (새 채팅방 생성 시 참여자 세션을 찾는 용도)
 * - ws:session-owners              Map   세션 ID → 소유 서버 ID
 * - ws:server-sessions:{serverId}  Set   서버가 가진 "userId:sessionId" (서버가 죽으면 한 번에 정리)
 * - ws:servers                     Set   알려진 서버 ID
 * - ws:server-alive:{serverId}     값    서버 heartbeat (TTL 이 지나면 죽은 서버로 본다)
 *
 * 세션이 "내 서버에 없다"는 것만으로는 죽은 세션이라고 판단할 수 없다 (다른 서버의 정상 세션일 수 있음).
 * 소유 서버의 heartbeat 가 만료된 경우에만 정리한다.
 */
@Slf4j
@Component
public class WebSocketSessionRegistry {

    private static final String SESSION_KEY_PREFIX = "ws:sessions:";
    private static final String OWNERS_KEY = "ws:session-owners";
    private static final String SERVER_SESSIONS_PREFIX = "ws:server-sessions:";
    private static final String SERVERS_KEY = "ws:servers";
    private static final String ALIVE_PREFIX = "ws:server-alive:";
    private static final String CLEANUP_LOCK_PREFIX = "ws:server-cleanup:";

    private final RedissonClient redissonClient;
    private final String serverId;
    private final Duration heartbeatTtl;

    public WebSocketSessionRegistry(
            RedissonClient redissonClient,
            WebSocketServerIdentity identity,
            @Value("${chat.ws.server-heartbeat-ttl-seconds:30}") long heartbeatTtlSeconds
    ) {
        this.redissonClient = redissonClient;
        this.serverId = identity.getServerId();
        this.heartbeatTtl = Duration.ofSeconds(heartbeatTtlSeconds);
    }

    public void register(Long userId, String sessionId) {
        RBatch batch = redissonClient.createBatch();
        addEntry(batch, userId, sessionId);
        batch.execute();
    }

    public Set<String> getSessionIds(Long userId) {
        return sessions(userId).readAll();
    }

    public void remove(Long userId, String sessionId) {
        RBatch batch = redissonClient.createBatch();
        batch.getSet(SESSION_KEY_PREFIX + userId, StringCodec.INSTANCE).removeAsync(sessionId);
        batch.getMap(OWNERS_KEY, StringCodec.INSTANCE).fastRemoveAsync(sessionId);
        batch.getSet(SERVER_SESSIONS_PREFIX + serverId, StringCodec.INSTANCE).removeAsync(entry(userId, sessionId));
        batch.execute();
    }

    /** 세션의 소유 서버 ID. 이 구조 도입 이전에 등록된 세션이면 null. */
    public String ownerOf(String sessionId) {
        return redissonClient.<String, String>getMap(OWNERS_KEY, StringCodec.INSTANCE).get(sessionId);
    }

    public boolean isServerAlive(String ownerServerId) {
        return redissonClient.getBucket(ALIVE_PREFIX + ownerServerId, StringCodec.INSTANCE).isExists();
    }

    public boolean isLocalServer(String ownerServerId) {
        return serverId.equals(ownerServerId);
    }

    /**
     * heartbeat 갱신 + 이 서버의 레지스트리 항목을 로컬 세션과 맞춘다 (reconcile).
     * - 로컬에 있는데 레지스트리에 없는 세션 → 다시 기록 (Redis 장애로 heartbeat 가 만료돼 다른 서버가 정리한 경우 복구)
     * - 레지스트리에 있는데 로컬에 없는 세션 → 제거 (종료 시 Redis 제거 실패, 종료와 heartbeat 재기록이 겹친 경우)
     *
     * 순서: 레지스트리를 먼저 읽고 로컬 스냅샷을 나중에 뜬다. 새 연결은 로컬 등록 → 레지스트리 등록 순서라,
     * 레지스트리에서 읽힌 세션은 반드시 로컬 스냅샷에도 있어 새 연결을 잘못 지우지 않는다.
     * 종료가 스냅샷 직후에 일어나면 이번 갱신이 다시 기록할 수 있지만 다음 갱신에서 제거된다 (최대 한 주기).
     *
     * @param localSnapshot 레지스트리를 읽은 뒤 호출되는 로컬 세션 스냅샷 (sessionId → userId)
     * @return 제거한 불일치 항목 수
     */
    public int heartbeat(Supplier<Map<String, Long>> localSnapshot) {
        Set<String> registered = redissonClient.<String>getSet(SERVER_SESSIONS_PREFIX + serverId, StringCodec.INSTANCE).readAll();
        Map<String, Long> local = localSnapshot.get();
        Set<String> localEntries = new HashSet<>();
        local.forEach((sessionId, userId) -> localEntries.add(entry(userId, sessionId)));

        RBatch batch = redissonClient.createBatch();
        batch.getBucket(ALIVE_PREFIX + serverId, StringCodec.INSTANCE).setAsync(serverId, heartbeatTtl);
        batch.getSet(SERVERS_KEY, StringCodec.INSTANCE).addAsync(serverId);
        local.forEach((sessionId, userId) -> addEntry(batch, userId, sessionId));
        int stale = 0;
        for (String e : registered) {
            if (localEntries.contains(e)) {
                continue;
            }
            int sep = e.indexOf(':');
            if (sep < 0) {
                continue;
            }
            String userId = e.substring(0, sep);
            String sessionId = e.substring(sep + 1);
            batch.getSet(SESSION_KEY_PREFIX + userId, StringCodec.INSTANCE).removeAsync(sessionId);
            batch.getMap(OWNERS_KEY, StringCodec.INSTANCE).fastRemoveAsync(sessionId);
            batch.getSet(SERVER_SESSIONS_PREFIX + serverId, StringCodec.INSTANCE).removeAsync(e);
            stale++;
        }
        batch.execute();
        return stale;
    }

    /**
     * heartbeat 가 만료된 서버의 세션을 정리한다. 여러 서버가 동시에 돌아도 서버별 락으로 한 번만 정리한다.
     *
     * @return 정리한 세션 수
     */
    public int cleanupDeadServers() {
        int cleaned = 0;
        for (String id : redissonClient.<String>getSet(SERVERS_KEY, StringCodec.INSTANCE).readAll()) {
            if (isLocalServer(id) || isServerAlive(id)) {
                continue;
            }
            RLock lock = redissonClient.getLock(CLEANUP_LOCK_PREFIX + id);
            boolean locked = false;
            try {
                locked = lock.tryLock(0, 30, TimeUnit.SECONDS);
                if (!locked || isServerAlive(id)) { // 락을 잡는 사이 살아났으면 정리하지 않는다
                    continue;
                }
                cleaned += cleanupServer(id);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return cleaned;
            } finally {
                if (locked && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
        return cleaned;
    }

    /** 정상 종료 시 heartbeat 를 지워 다른 서버가 바로 정리할 수 있게 한다. */
    public void markDown() {
        redissonClient.getBucket(ALIVE_PREFIX + serverId, StringCodec.INSTANCE).delete();
    }

    private int cleanupServer(String deadServerId) {
        RSet<String> owned = redissonClient.getSet(SERVER_SESSIONS_PREFIX + deadServerId, StringCodec.INSTANCE);
        Collection<String> entries = owned.readAll();
        RBatch batch = redissonClient.createBatch();
        for (String e : entries) {
            int sep = e.indexOf(':');
            if (sep < 0) {
                continue;
            }
            String userId = e.substring(0, sep);
            String sessionId = e.substring(sep + 1);
            batch.getSet(SESSION_KEY_PREFIX + userId, StringCodec.INSTANCE).removeAsync(sessionId);
            batch.getMap(OWNERS_KEY, StringCodec.INSTANCE).fastRemoveAsync(sessionId);
        }
        batch.getSet(SERVER_SESSIONS_PREFIX + deadServerId, StringCodec.INSTANCE).deleteAsync();
        batch.getSet(SERVERS_KEY, StringCodec.INSTANCE).removeAsync(deadServerId);
        batch.execute();
        log.warn("[WS Registry] 죽은 서버의 세션 정리 - server={}, sessions={}", deadServerId, entries.size());
        return entries.size();
    }

    private void addEntry(RBatch batch, Long userId, String sessionId) {
        batch.getSet(SESSION_KEY_PREFIX + userId, StringCodec.INSTANCE).addAsync(sessionId);
        batch.getMap(OWNERS_KEY, StringCodec.INSTANCE).fastPutAsync(sessionId, serverId);
        batch.getSet(SERVER_SESSIONS_PREFIX + serverId, StringCodec.INSTANCE).addAsync(entry(userId, sessionId));
    }

    private RSet<String> sessions(Long userId) {
        return redissonClient.getSet(SESSION_KEY_PREFIX + userId, StringCodec.INSTANCE);
    }

    private static String entry(Long userId, String sessionId) {
        return userId + ":" + sessionId;
    }
}
