-- Redis 발행 중에는 DB 잠금을 유지하지 않고 만료 가능한 선점으로 폴러를 조정한다.
ALTER TABLE outbox_event
    ADD COLUMN claim_token VARCHAR(36) NULL,
    ADD COLUMN claimed_until DATETIME(6) NULL;
