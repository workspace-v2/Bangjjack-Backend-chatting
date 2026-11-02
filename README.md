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
