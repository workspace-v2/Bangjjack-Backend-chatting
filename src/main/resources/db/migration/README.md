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
