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
