-- TossPayments 테스트 연동 결제 승인 결과를 원장에 남기기 위한 컬럼 추가.
-- payment_key: Toss confirm 응답의 결제 식별자. 원장당 유일해야 하므로 단일 컬럼 UNIQUE 제약을 둔다.
--   기존 uk_txn_wallet_idempotency_key(wallet_id, idempotency_key)와는 컬럼 구성과 용도가 달라
--   서로 다른 스코프의 제약이며 충돌하지 않는다. MySQL은 UNIQUE 컬럼에 다수의 NULL을 허용하므로
--   payment_key가 없는 기존/비-Toss 거래 행에도 안전하게 적용된다.
-- order_id: Toss confirm 요청에 사용한 주문번호. 조회 인덱스는 의도적으로 두지 않는다 —
--   데모 규모(수백 row)에서는 사람이 던지는 단발 조회로 충분하고, 이 컬럼의 존재 목적은
--   "감사 로그가 특정 orderId를 불확정/보상실패로 표시했을 때, 그 orderId에 해당하는 원장 row가
--   실제로 존재하는가?"라는 역방향 조회(수동 대사)에 답하기 위함이다.
--
-- 롤백 절차 (Flyway 9.22.3은 undo 마이그레이션을 지원하지 않음):
--   이 기능이 폐기되면 이 파일을 사후 편집하지 않고, 새 마이그레이션 V19에서 아래를 실행한다.
--     ALTER TABLE `transaction`
--         DROP INDEX `uk_txn_payment_key`,
--         DROP COLUMN `payment_key`,
--         DROP COLUMN `order_id`;

ALTER TABLE `transaction`
    ADD COLUMN `payment_key` VARCHAR(200) NULL COMMENT 'Toss 결제 승인 응답의 결제 식별자' AFTER `auto_tagged`,
    ADD COLUMN `order_id` VARCHAR(200) NULL COMMENT 'Toss 결제 요청 주문번호(역방향 수동 대사용, 인덱스 없음)' AFTER `payment_key`;

ALTER TABLE `transaction`
    ADD CONSTRAINT `uk_txn_payment_key` UNIQUE (`payment_key`);
