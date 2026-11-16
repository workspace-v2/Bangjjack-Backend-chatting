-- Transactional Outbox: 방 생성 이벤트를 방과 같은 트랜잭션에 저장해, Redis 장애가 길어도 유실 없이 발행한다.
-- 기존(V5 이하)은 발행 실패분이 메모리 재시도(3분)에만 남아 그 이상 장애면 유실됐다 (PR #41 리뷰 지적).
--
-- 새 테이블 추가라 롤링 배포 중 구버전 서버와 호환된다 (구버전은 이 테이블을 쓰지 않음).
CREATE TABLE outbox_event (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    event_type   VARCHAR(50)  NOT NULL,                -- 'ROOM_CREATED'
    room_id      BIGINT       NULL,                     -- 발행 완료 표시 상관키 (방은 한 번만 생성됨)
    payload      TEXT         NOT NULL,                 -- 발행할 이벤트 JSON (delayed=false 기본형)
    created_at   DATETIME(6)  NOT NULL,
    published_at DATETIME(6)  NULL,                      -- NULL = 미발행
    PRIMARY KEY (id),
    KEY IDX_outbox_unpublished (published_at, id)        -- 미발행분 폴링 (published_at IS NULL ORDER BY id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
