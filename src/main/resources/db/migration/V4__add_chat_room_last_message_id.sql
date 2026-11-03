-- 채팅방 마지막 메시지 id: 채팅방 목록에서 방마다 마지막 메시지를 PK 로 바로 찾기 위함
-- 이전 조회(chat.id IN (SELECT MAX(id) ... GROUP BY room_id))는 바깥 쿼리가 chat 전체를 스캔해
-- 메시지가 쌓일수록 목록 조회가 느려졌다 (EXPLAIN type ALL, 호출당 chat 전체 행).
--
-- 롤링 배포 호환 (expand): NULL 허용으로 추가하고 백필한다. 마이그레이션 직후에도 구버전 서버가 메시지를 저장하며
-- 이 값을 갱신하지 않으므로, 구버전이 모두 내려간 뒤 아래 백필 UPDATE 를 한 번 더 실행해 맞춘다 (README 참고).
-- 그 사이 해당 방의 목록 마지막 메시지는 다음 메시지가 저장될 때까지 한 건 이전 메시지로 보일 수 있다.

-- 메타데이터 락을 오래 기다리지 않는다 (V3 와 같은 이유)
SET SESSION lock_wait_timeout = 10;

-- 끝에 NULL 허용 컬럼 추가는 INSTANT 로 메타데이터만 바뀐다. 불가하면 테이블 복사로 넘어가지 않고 실패한다
ALTER TABLE chat_room ADD COLUMN last_message_id BIGINT NULL, ALGORITHM=INSTANT;

-- 기존 방 백필: 삭제되지 않은 메시지 중 가장 큰 id (이전 조회와 같은 의미)
UPDATE chat_room r
JOIN (
    SELECT room_id, MAX(id) AS last_id
    FROM chat
    WHERE deleted = FALSE
    GROUP BY room_id
) latest ON latest.room_id = r.id
SET r.last_message_id = latest.last_id;
