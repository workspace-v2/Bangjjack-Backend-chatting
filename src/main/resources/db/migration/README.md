# DB 마이그레이션 (Flyway)

스키마는 Flyway 가 관리한다. `spring.jpa.hibernate.ddl-auto` 는 `none` 이다 (dev/local).

- `V1__baseline.sql`: 도입 시점의 실제 DB 스키마 덤프 (`terraform/scripts/dump-schema.sh dev`).
  이미 테이블이 있는 main/dev DB 는 `baseline-on-migrate` 로 V1 을 실행하지 않고 기준점(version 1)으로만 기록한다.
  새 DB(로컬 등)는 V1 부터 실행된다.
- 엔티티를 바꾸면 같은 변경을 `V{다음 번호}__{설명}.sql` 로 추가한다. 예: `V2__drop_chat_room_unread_count.sql`
- 이미 배포된 V 파일은 수정하지 않는다 (checksum 검증 실패로 앱이 뜨지 않는다). 고칠 내용은 새 버전으로 추가한다.
- 테스트는 H2 + `ddl-auto: create-drop` 이며 Flyway 를 끈다 (`application-test.yaml`).

## 롤링 배포 호환 (expand → contract)

ECS 롤링 배포 중에는 새 서버가 Flyway 를 실행하는 동안에도 구버전 서버가 쓰기를 계속한다.
구버전이 모르는 컬럼에 NOT NULL 을 바로 걸면 구버전 INSERT 가 실패하므로 두 번에 나눠 배포한다.

- expand: 새 컬럼은 NULL 허용으로 추가 + 백필. 새 코드는 값을 채워 저장한다.
- contract: 모든 서버가 새 버전이 된 뒤 다음 배포에서 NULL 정리 + NOT NULL 을 적용한다.

### chat.seq (V2 = expand, V3 = contract)

`V2__add_chat_seq.sql` 은 seq 를 NULL 허용으로 추가했고, `V3__chat_seq_not_null.sql` 이 NOT NULL 을 적용한다.
V3 배포 전에 확인 (main/dev 모두 0 확인 후 적용):

```sql
SELECT COUNT(*) FROM chat WHERE seq IS NULL; -- 롤링 배포 중 구버전이 저장한 메시지
```

- 0 이면: `ALTER TABLE chat MODIFY seq BIGINT NOT NULL;` 만 추가하면 된다.
- 0 이 아니면: 해당 방의 last_seq 뒤에 이어 붙이도록 seq 를 부여하고 last_seq 를 갱신한 뒤 NOT NULL 을 건다
  (부하가 없는 시간에 적용. 동시에 새 메시지가 저장되면 seq 가 충돌할 수 있다).

V3 적용 후 주의:
- 애플리케이션 롤백은 V2 이상 코드로만 한다. V2 이전 코드는 seq 없이 INSERT 하므로 NOT NULL 에 걸려 전송이 실패한다.
- V3 가 실패하면(메타데이터 락 timeout, 비 STRICT sql_mode, NULL 행 존재) 앱이 기동하지 않고 ECS 가 이전 리비전으로 롤백한다.
  ALTER 는 원자적으로 실패하므로 스키마는 V2 상태 그대로다. 원인을 해결한 뒤 재배포한다.
- 배포 후 확인: `SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;`, `SHOW COLUMNS FROM chat LIKE 'seq';` (Null = NO)

### chat_room.last_message_id (V4 = expand)

채팅방 목록이 방마다 마지막 메시지를 PK 로 바로 찾도록 `last_message_id` 를 NULL 허용으로 추가하고 백필한다
(이전 `chat.id IN (SELECT MAX(id) ... GROUP BY room_id)` 는 chat 전체 스캔). NOT NULL 은 걸지 않는다 (메시지 없는 방은 NULL).

롤링 배포 중 구버전 서버가 저장한 메시지는 이 값을 갱신하지 않는다. 구버전 태스크가 모두 내려간 뒤 한 번 더 맞춘다:

```sql
UPDATE chat_room r
JOIN (SELECT room_id, MAX(id) AS last_id FROM chat WHERE deleted = FALSE GROUP BY room_id) latest ON latest.room_id = r.id
SET r.last_message_id = latest.last_id
WHERE r.last_message_id IS NULL OR r.last_message_id < latest.last_id;
```

- 배포 후 확인: `SHOW COLUMNS FROM chat_room LIKE 'last_message_id';`, 위 UPDATE 의 변경 행 수 (0 이면 이미 일치)
- 롤백: 구버전 코드는 이 컬럼을 읽지 않으므로 롤백해도 문제없다. 다시 새 버전으로 올릴 때 위 UPDATE 를 실행한다
- 메시지 삭제(soft delete) 기능이 생기면 마지막 메시지 삭제 시 이 값을 다시 계산해야 한다 (현재 메시지 삭제 경로 없음)

### 조회 인덱스 (V5)

`IDX_chat_room_deleted_id (room_id, deleted, id)`: 메시지 페이지가 PRIMARY 역방향 스캔 대신 방 단위로 필요한 행만 읽도록.
`IDX_participant_user_status (user_id, status, deleted)`: 채팅방 목록의 내 참여 방 조회가 참여자 테이블 전체를 읽지 않도록.
인덱스 추가만이라 구버전과 호환된다. `ALGORITHM=INPLACE, LOCK=NONE` 으로 쓰기를 막지 않고 만든다.

- 배포 후 확인: 실행 계획이 새 인덱스를 쓰는지
  ```sql
  EXPLAIN SELECT * FROM chat WHERE room_id = ? AND id < 9223372036854775807 AND deleted = 0 ORDER BY id DESC LIMIT 31; -- key = IDX_chat_room_deleted_id
  EXPLAIN SELECT * FROM chat_room_participant WHERE user_id = ? AND status = 'ACTIVE' AND deleted = 0;           -- key = IDX_participant_user_status
  ```
- 메시지 페이지는 인덱스를 추가해도 옵티마이저가 PRIMARY 역방향 스캔을 골랐다 (prefer_ordering_index). 그래서 `ChatRepository` 의
  메시지 페이지 쿼리 2개가 네이티브 SQL `FORCE INDEX (IDX_chat_room_deleted_id)` 를 쓴다. **이 인덱스 이름을 바꾸거나 지우면 쿼리가 실패한다.**

### Outbox 이벤트 테이블 (V6)

`outbox_event`: 방 생성 이벤트를 방과 같은 트랜잭션에 저장해(원자적 커밋) Redis 장애가 길어져도 유실 없이 발행한다.
기존 V5 이하는 발행 실패분을 메모리 재시도(최대 3분)로만 버텨 그 이상 장애면 유실됐다 (PR #41 리뷰 지적).

- 흐름: 방 생성 트랜잭션에서 `record`(BEFORE_COMMIT)가 미발행 행을 저장 → 커밋 직후 `publishImmediately`(AFTER_COMMIT)가
  즉시 발행하고 성공하면 `published_at` 을 채운다 → 실패(Redis 장애)하면 미발행으로 남고 폴러(`OutboxPublisher`)가 복구 때까지 재발행한다.
- 폴러는 `published_at IS NULL AND created_at <= now-grace` 를 `FOR UPDATE SKIP LOCKED` 로 잠근 채 발행까지 한 트랜잭션에서 처리해,
  다중 서버가 같은 행을 중복 발행하지 않는다. grace(기본 3초)는 즉시 발행이 표시할 유예 시간.
- 폴러가 발행하는 이벤트는 `delayed=true` 로 표시돼, 수신 서버가 그 사이 새로 구독한 세션에 재동기화 신호(SYNC_REQUIRED)를 보낸다.
- `IDX_outbox_unpublished (published_at, id)`: 미발행분 폴링용. 발행 완료 행은 `cleanup`(기본 1시간 주기, 보관 60분)으로 삭제해 테이블 증가를 막는다.
- 설정: `chat.outbox.poll-interval-ms`(1000), `publish-grace-ms`(3000), `batch-size`(100), `retention-minutes`(60), `cleanup-interval-ms`(3600000).
- 새 테이블 추가라 구버전과 호환된다 (expand). 배포 후 확인: `SHOW TABLES LIKE 'outbox_event';`, `SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL;` (정상 운영 중 0 수렴).
- 지표: `chat.outbox.pending`(미발행 수, Gauge), `chat.outbox.recovered`(폴러가 재발행한 수, Counter).
