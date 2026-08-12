-- 애월지갑 출금 요청 멱등성 기록.
-- 같은 회원이 같은 Idempotency-Key를 재사용하면 최초 출금 결과를 그대로 반환한다.
CREATE TABLE IF NOT EXISTS `wallet_withdrawal_request` (
    `withdrawal_request_id`   BIGINT         NOT NULL AUTO_INCREMENT,
    `member_id`               BIGINT         NOT NULL,
    `idempotency_key`         VARCHAR(64)    NOT NULL,
    `request_hash`            CHAR(64)       NOT NULL,
    `status`                  VARCHAR(20)    NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / COMPLETED',
    `account_id`              BIGINT         NOT NULL,
    `transaction_id`          BIGINT         NULL,
    `wallet_balance_after`    DECIMAL(15,2)  NULL,
    `bank_name`               VARCHAR(100)   NULL,
    `account_number_masked`   VARCHAR(100)   NULL,
    `withdrawn_at`            DATETIME       NULL,
    `created_at`              DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`              DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`withdrawal_request_id`),
    UNIQUE KEY `uq_withdrawal_member_idempotency` (`member_id`, `idempotency_key`),
    KEY `idx_withdrawal_transaction` (`transaction_id`),
    CONSTRAINT `fk_withdrawal_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`),
    CONSTRAINT `fk_withdrawal_account` FOREIGN KEY (`account_id`) REFERENCES `linked_account` (`account_id`),
    CONSTRAINT `fk_withdrawal_transaction` FOREIGN KEY (`transaction_id`) REFERENCES `transaction` (`txn_id`),
    CONSTRAINT `chk_withdrawal_status` CHECK (`status` IN ('PENDING', 'COMPLETED')),
    CONSTRAINT `chk_withdrawal_completion` CHECK (
        (`status` = 'PENDING' AND `transaction_id` IS NULL AND `wallet_balance_after` IS NULL
            AND `bank_name` IS NULL AND `account_number_masked` IS NULL AND `withdrawn_at` IS NULL)
        OR
        (`status` = 'COMPLETED' AND `transaction_id` IS NOT NULL AND `wallet_balance_after` IS NOT NULL
            AND `bank_name` IS NOT NULL AND `account_number_masked` IS NOT NULL AND `withdrawn_at` IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
