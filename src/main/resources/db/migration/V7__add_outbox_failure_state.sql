-- 데이터 오류는 발행 완료와 구분해 보존하고 폴링 대상에서 제외한다.
ALTER TABLE outbox_event
    ADD COLUMN failed_at DATETIME(6) NULL,
    ADD COLUMN failure_reason TEXT NULL,
    DROP INDEX IDX_outbox_unpublished,
    ADD INDEX IDX_outbox_unpublished (published_at, failed_at, id);
