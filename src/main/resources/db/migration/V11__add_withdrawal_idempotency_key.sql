-- 출금 재시도 시 동일한 저금통에서 중복 이체가 발생하지 않도록 요청 키를 저장한다.
-- NULL은 기존 거래와 출금 이외 거래에서 허용하고, 출금 요청은 애플리케이션에서 필수로 검증한다.
ALTER TABLE `transaction`
    ADD COLUMN `idempotency_key` VARCHAR(64) NULL COMMENT '출금 요청 재시도 식별 키' AFTER `auto_tagged`;

ALTER TABLE `transaction`
    ADD CONSTRAINT `uk_txn_wallet_idempotency_key` UNIQUE (`wallet_id`, `idempotency_key`);
