-- 정기결제 등록 재시도가 첫 회차를 두 번 받지 않도록 요청 멱등키를 둔다.
-- 같은 지갑에서 같은 키로 다시 오면 기존 등록을 그대로 반환한다.
ALTER TABLE `recurring_payment`
    ADD COLUMN `idempotency_key` VARCHAR(64) NULL COMMENT '등록 요청 재시도 식별 키' AFTER `wallet_id`;

ALTER TABLE `recurring_payment`
    ADD UNIQUE KEY `uk_recurring_wallet_idempotency_key` (`wallet_id`, `idempotency_key`);
