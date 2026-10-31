# DB 마이그레이션 (Flyway)

스키마는 Flyway 가 관리한다. `spring.jpa.hibernate.ddl-auto` 는 `none` 이다 (dev/local).

- `V1__baseline.sql`: 도입 시점의 실제 DB 스키마 덤프 (`terraform/scripts/dump-schema.sh dev`).
  이미 테이블이 있는 main/dev DB 는 `baseline-on-migrate` 로 V1 을 실행하지 않고 기준점(version 1)으로만 기록한다.
  새 DB(로컬 등)는 V1 부터 실행된다.
- 엔티티를 바꾸면 같은 변경을 `V{다음 번호}__{설명}.sql` 로 추가한다. 예: `V2__drop_chat_room_unread_count.sql`
- 이미 배포된 V 파일은 수정하지 않는다 (checksum 검증 실패로 앱이 뜨지 않는다). 고칠 내용은 새 버전으로 추가한다.
- 테스트는 H2 + `ddl-auto: create-drop` 이며 Flyway 를 끈다 (`application-test.yaml`).
