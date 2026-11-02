-- 방별 메시지 순번(seq): 클라이언트가 누락(gap)을 감지하고 동기화 API 로 빠진 메시지를 가져오기 위함
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

ALTER TABLE chat MODIFY seq BIGINT NOT NULL;
ALTER TABLE chat ADD CONSTRAINT UQ_chat_room_seq UNIQUE (room_id, seq);
