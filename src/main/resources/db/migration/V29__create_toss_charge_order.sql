-- TossPayments 충전 승인 전 주문 정보.
-- 서버에서 발급한 order_id와 회원·금액을 보관해 승인 요청의 소유권과 금액을 검증한다.
CREATE TABLE IF NOT EXISTS `toss_charge_order` (
    `order_id`   VARCHAR(64)    NOT NULL,
    `member_id`  BIGINT         NOT NULL,
    `amount`     DECIMAL(15,2)  NOT NULL,
    `status`     VARCHAR(10)    NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / APPROVED',
    `created_at` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`order_id`),
    KEY `idx_tco_member` (`member_id`),
    CONSTRAINT `fk_tco_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
