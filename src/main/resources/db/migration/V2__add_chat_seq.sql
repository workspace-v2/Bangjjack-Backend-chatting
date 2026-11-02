-- 방별 메시지 순번(seq): 클라이언트가 누락(gap)을 감지하고 동기화 API 로 빠진 메시지를 가져오기 위함
--
-- 롤링 배포 호환 (expand 단계): 이 마이그레이션이 도는 동안과 직후에도 구버전 서버가 seq 없이 메시지를 저장한다.
-- 그래서 seq 는 NULL 허용으로 두고 NOT NULL 은 걸지 않는다 (구버전 INSERT 실패, 백필 후 NULL 행으로 인한 마이그레이션 실패 방지).
-- 모든 서버가 seq 를 기록하는 버전으로 바뀐 뒤 별도 배포에서 남은 NULL 처리 + NOT NULL 을 적용한다 (README 참고).
-- 그 사이 구버전이 저장한 seq NULL 메시지는 동기화 대상에서 빠진다 (메시지 조회 API 로는 보인다).
ALTER TABLE chat_room ADD COLUMN last_seq BIGINT NOT NULL DEFAULT 0;
ALTER TABLE chat ADD COLUMN seq BIGINT NULL;

-- 기존 메시지 백필: 방마다 id 순서로 1부터 부여 (soft delete 된 메시지도 포함해 순번을 유지)
UPDATE chat c
JOIN (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY room_id ORDER BY id) AS rn
    FROM chat
) numbered ON numbered.id = c.id
SET c.seq = numbered.rn;

UPDATE chat_room r
SET r.last_seq = COALESCE((SELECT MAX(c.seq) FROM chat c WHERE c.room_id = r.id), 0);

-- NULL 은 UNIQUE 에 걸리지 않으므로 구버전이 넣은 seq NULL 행과 공존한다
ALTER TABLE chat ADD CONSTRAINT UQ_chat_room_seq UNIQUE (room_id, seq);
