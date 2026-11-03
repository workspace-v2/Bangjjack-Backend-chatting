# Bangjjack-Backend

## 테스트

```bash
./gradlew test
```

채팅 동시성 통합 테스트는 실제 MySQL의 REPEATABLE READ, 행 락, UPDATE의 현재 읽기를 검증합니다.
`CHAT_TEST_MYSQL_URL`이 없으면 해당 테스트만 건너뜁니다.
테스트가 Hibernate `create-drop`을 사용하므로 반드시 별도로 만든 폐기 가능한 테스트 DB를 지정하세요.
서버 버전은 배포 환경과 맞추는 것을 권장합니다.

```bash
CHAT_TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:3306/chat_state_test' \
CHAT_TEST_MYSQL_USER='test_user' \
CHAT_TEST_MYSQL_PASSWORD='test_password' \
./gradlew test --tests '*ChatStateMysqlIntegrationTest'
```

검증 항목: LEFT 참여자를 미리 조회한 두 전송의 unread 누적, 오래된 스냅샷에서의 읽음·퇴장,
READ 커서의 단조 증가, 잘못된 메시지 ID 거부, 재입장 응답의 최신 상태,
카테고리 변경 시 동시 전송의 마지막 메시지 시각 보존.

## 다중 서버 WebSocket 세션 관리

각 서버는 자신에게 연결된 세션과 방 구독을 로컬 `WebSocketSessionStore`에서 관리합니다.
방 생성·채팅 메시지·읽음 이벤트는 Redis Pub/Sub으로 각 서버에 전달하고,
수신 서버는 자신의 로컬 세션에만 구독 또는 전송을 적용합니다.
전역 세션 목록을 조회하는 기능이 없어 Redis 세션 레지스트리와 서버 heartbeat·sweep은 사용하지 않습니다.
서버가 종료되면 로컬 세션 정보도 사라지므로 다른 서버가 종료된 서버의 세션을 삭제할 필요가 없습니다.

클라이언트 연결 확인은 서버마다 실행하는 `WebSocketKeepAlive`가 담당합니다.
WebSocket I/O는 전용 `webSocketKeepAliveScheduler`를 사용하며 일반 예약 작업의 `taskScheduler`와 분리합니다.
`chat.ws.ping-interval-ms`(기본 20000), `chat.ws.pong-timeout-ms`(기본 60000)로 주기를 설정합니다.
`chat.ws.sessions.local`, `chat.ws.pong.timeout.closed` 메트릭은 유지합니다.

이전 버전의 `ws:sessions:*`, `ws:session-owners`, `ws:server-sessions:*`, `ws:servers` 키는
새 버전에서 읽거나 갱신하지 않습니다. 구버전 서버가 모두 종료된 뒤 해당 레지스트리 키를 별도로 정리할 수 있습니다.
기존 `chat.ws.server-heartbeat-*`, `chat.ws.zombie-sweep-*` 설정과 레지스트리 정리 메트릭은 더 이상 사용하지 않습니다.
WebSocket 연결 시 DB에서 참여 방 목록을 조회해 초기 구독합니다.

Redis 연결이 끊긴 동안 발행된 방 생성 이벤트는 다시 전달되지 않으므로 다음 두 경로로 복구합니다.
- 수신 쪽: 방 생성 채널이 재구독되면(`onSubscribe`) 로컬 세션의 참여 방을 DB에서 다시 조회해 빠진 방을 구독하고,
  새로 구독한 방에 `SYNC_REQUIRED`를 보내 끊긴 동안의 메시지를 동기화 API로 받게 합니다 (`chat.ws.room-subscription.recovered`).
  클라이언트가 `UNSUBSCRIBE`로 직접 해제한 방은 다시 구독하지 않으며, 참여 방 조회에 실패한 사용자는
  `chat.ws.room-subscription.recovery-retry-delay-ms`(기본 5000) 간격으로 최대 `recovery-max-retries`(기본 5)회 다시 복구합니다
  (한도 초과 `chat.ws.room-subscription.recovery.failed`).
- 발행 쪽: 이 서버의 Redis 연결이 끊겨 발행에 실패하면 다른 서버는 재구독이 일어나지 않아 알 수 없으므로,
  발행 대기열 초과로 버려진 경우도 포함해 `chat.pubsub.room-created.retry-interval-ms`(기본 5000) 간격으로, 첫 실패부터 `chat.pubsub.room-created.retry-for-ms`(기본 180000 = 3분) 동안 다시 발행합니다.
  3분은 측정한 Redis 최대 다운 시간(약 2.5분)보다 긴 값이며, 더 긴 장애는 클라이언트 재연결 시 DB 기준 초기 구독으로 복구됩니다.
  재발행 이벤트는 `delayed`로 표시해 수신 서버가 새로 구독한 세션에 `SYNC_REQUIRED`를 보냅니다 (한도 초과 `chat.pubsub.room-created.retry.exhausted`).
- `delayed`는 false일 때 JSON에서 빠지므로 일반 이벤트 형식은 이전 버전과 같습니다.
