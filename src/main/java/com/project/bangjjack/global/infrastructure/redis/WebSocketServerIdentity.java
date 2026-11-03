package com.project.bangjjack.global.infrastructure.redis;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.UUID;

/**
 * 이 서버 프로세스의 식별자. 세션 레지스트리에 세션의 소유 서버로 기록된다.
 * 재시작하면 새 값이 된다 (이전 프로세스의 세션은 이전 식별자의 heartbeat 만료로 정리된다).
 */
@Getter
@Component
public class WebSocketServerIdentity {

    private final String serverId;

    public WebSocketServerIdentity() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        this.serverId = host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
