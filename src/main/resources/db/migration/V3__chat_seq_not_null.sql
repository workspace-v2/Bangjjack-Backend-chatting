-- chat.seq contract 단계: V2(expand)에서 NULL 허용으로 추가한 seq 에 NOT NULL 적용
-- 전제: main/dev 모든 서버가 seq 를 기록하는 버전으로 배포됐고, seq NULL 행이 0건임을 확인함
-- NULL 행이 남아 있으면 이 마이그레이션은 실패하고 앱이 기동하지 않는다 (README 의 정리 절차 후 재배포)
ALTER TABLE chat MODIFY seq BIGINT NOT NULL;
