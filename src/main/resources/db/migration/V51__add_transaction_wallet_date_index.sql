-- V51: 거래내역 조회·대시보드 집계용 복합 인덱스
--
-- 거래내역과 대시보드가 모두 "이 지갑의, 이 기간" 형태로 조회한다. 그런데 인덱스는
-- wallet_id 하나, txn_date 하나로 따로 있었다. 그래서 지갑의 거래를 전부 읽은 뒤 날짜로
-- 걸러내고, 다시 정렬해야 했다.
--
-- 목록 쿼리는 ORDER BY txn_date DESC, txn_id DESC로 끝난다. 이 순서를 인덱스가 그대로
-- 담고 있으면 정렬 자체가 사라지고, LIMIT만큼만 읽고 멈출 수 있다. txn_id까지 넣는 이유가
-- 여기 있다 — 커서 페이지네이션의 정렬 키와 같아야 한다.
--
-- 지갑 500개 × 거래 1,000건(총 50만 건) 기준 측정.
--
--   거래내역 20건 조회
--     현재   1,000행 읽고 정렬(filesort)
--     개선      20행, 정렬 없음
--
--   대시보드 월별 합계
--     현재   1,000행
--     개선      45행
--
-- (wallet_id, txn_type, txn_date)도 만들어 비교했다. 대시보드는 23행으로 조금 더 낫지만
-- 목록 쿼리의 정렬을 없애지 못한다. 현재 사용자 흐름상 목록 조회가 더 잦을 것으로 보고
-- 정렬 제거를 택했다. 아직 운영 요청 지표가 없어 이는 추정이며, #343 메트릭이 쌓이면
-- 실제 호출량을 확인해 다시 판단한다. 대시보드는 한 달치 범위라 45행으로도 충분히 빠르다.
--
-- transaction은 결제가 계속 들어오는 원장이다. MySQL이 환경에 따라 더 강한 잠금 방식으로
-- 조용히 폴백하지 못하도록 online DDL을 강제한다. 현재 엔진에서 LOCK=NONE을 지원하지 않으면
-- 배포 중 쓰기를 막는 대신 마이그레이션이 실패해야 한다.
-- 50만 행 측정은 인덱스 선택의 근거일 뿐 운영 DDL 소요 시간의 근거는 아니다. 배포 전 실제
-- 행 수와 테이블·인덱스 크기를 확인하고, 운영 규모에서 걸린 시간을 기준으로 배포 창을 잡는다.
ALTER TABLE `transaction`
    ADD INDEX `idx_txn_wallet_date` (`wallet_id`, `txn_date`, `txn_id`),
    ALGORITHM=INPLACE,
    LOCK=NONE;

-- idx_txn_wallet(wallet_id)은 이제 위 인덱스의 접두사라 중복이다.
--
-- 사실 이 인덱스는 처음부터 필요 없었다. uk_txn_wallet_idempotency_key가
-- (wallet_id, idempotency_key)로 이미 wallet_id를 선두에 두고 있어, 옵티마이저도
-- idx_txn_wallet 대신 그쪽을 골라 쓰고 있었다.
--
-- 지우고 나서 실행 계획을 다시 확인했다. 목록 쿼리는 새 인덱스를 스스로 고르고,
-- wallet_id 없이 txn_date만 보는 잔돈 적립 배치(findTodayRoundUpCandidates)는 그대로
-- idx_txn_date를 쓴다. 그래서 idx_txn_date는 남긴다.
-- 새 인덱스를 먼저 만든 뒤 삭제하므로 fk_txn_wallet이 요구하는 wallet_id 선두 인덱스가
-- 사라지는 순간은 없다. 삭제도 쓰기를 막지 않는 방식만 허용한다.
ALTER TABLE `transaction`
    DROP INDEX `idx_txn_wallet`,
    ALGORITHM=INPLACE,
    LOCK=NONE;
