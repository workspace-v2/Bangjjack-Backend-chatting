-- chat.seq contract 단계: V2(expand)에서 NULL 허용으로 추가한 seq 에 NOT NULL 적용
-- 전제: main/dev 모든 서버가 seq 를 기록하는 버전(V2 이상)으로 배포됐고, seq NULL 행이 0건임을 확인함
-- NULL 행이 남아 있으면 이 마이그레이션은 실패하고 앱이 기동하지 않는다 (README 의 정리 절차 후 재배포)

-- 메타데이터 락을 오래 기다리지 않는다. 장기 트랜잭션이 테이블을 잡고 있으면 대기 중인 ALTER 뒤로
-- 다른 쿼리까지 줄줄이 막히므로, 10초 안에 못 잡으면 실패시키고 원인을 확인한 뒤 재배포한다.
SET SESSION lock_wait_timeout = 10;

-- ALGORITHM=INPLACE, LOCK=NONE: 온라인으로 수행할 수 없으면 테이블 복사·쓰기 잠금으로 넘어가지 않고 실패한다.
-- NULL -> NOT NULL 의 in-place 변경은 STRICT sql_mode 에서만 허용되므로, STRICT 가 아니면 이 문장이 실패한다
-- (비 STRICT 에서 NULL 이 0 으로 조용히 바뀌는 것을 막는 확인 역할도 한다).
ALTER TABLE chat MODIFY seq BIGINT NOT NULL, ALGORITHM=INPLACE, LOCK=NONE;
