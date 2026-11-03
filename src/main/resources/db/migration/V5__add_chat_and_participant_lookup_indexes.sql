-- 요청당 DB 커넥션 점유 시간 단축을 위한 조회 인덱스
--
-- 1) 메시지 페이지 (room_id = ? AND id < ? AND deleted = FALSE ORDER BY id DESC LIMIT ?)
--    (room_id, id) 인덱스가 있어도 옵티마이저가 정렬을 위해 PRIMARY 역방향 스캔을 골라, 조건에 맞는 행을 찾을 때까지
--    다른 방 메시지까지 읽었다 (EXPLAIN key PRIMARY, rows 51,125, filtered 6%).
--    (room_id, deleted, id) 는 조건(방, 삭제 여부)과 정렬(id)을 모두 인덱스 순서로 만족해 필요한 행만 읽는다.
-- 2) 채팅방 목록의 내 참여 방 (user_id = ? AND status = ? AND deleted = FALSE)
--    user_id 로 시작하는 인덱스가 없어 참여자 테이블 전체를 읽었다 (EXPLAIN type ALL).
--
-- 인덱스 추가만 하므로 롤링 배포 중 구버전 서버와 호환된다 (expand/contract 불필요).

-- 메타데이터 락을 오래 기다리지 않는다 (V3 와 같은 이유)
SET SESSION lock_wait_timeout = 10;

-- 온라인으로 만들 수 없으면 테이블 잠금으로 넘어가지 않고 실패한다
ALTER TABLE chat ADD INDEX IDX_chat_room_deleted_id (room_id, deleted, id), ALGORITHM=INPLACE, LOCK=NONE;
ALTER TABLE chat_room_participant ADD INDEX IDX_participant_user_status (user_id, status, deleted), ALGORITHM=INPLACE, LOCK=NONE;
