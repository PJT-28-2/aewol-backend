-- ================================================================
-- 애월 (AeWol) — DDL Schema
-- 반려동물 전용 전자지갑 서비스
-- MySQL 8.0 / InnoDB / utf8mb4
-- ================================================================

-- 1. MEMBER (회원)
CREATE TABLE IF NOT EXISTS `member` (
    `member_id`       VARCHAR(36)   NOT NULL,
    `email`           VARCHAR(100)  NOT NULL,
    `password`        VARCHAR(255)  NULL COMMENT '소셜 로그인 시 null',
    `nickname`        VARCHAR(30)   NOT NULL,
    `name`            VARCHAR(20)   NOT NULL,
    `phone`           VARCHAR(20)   NULL,
    `profile_img`     VARCHAR(500)  NULL,
    `provider`        VARCHAR(10)   NOT NULL DEFAULT 'LOCAL' COMMENT 'KAKAO / LOCAL',
    `provider_id`     VARCHAR(100)  NULL,
    `email_verified`  CHAR(1)       NOT NULL DEFAULT 'N' COMMENT 'Y/N',
    `role`            VARCHAR(10)   NOT NULL DEFAULT 'USER' COMMENT 'USER / ADMIN',
    `region`          VARCHAR(50)   NULL COMMENT '시/군/구',
    `income_level`    VARCHAR(20)   NULL COMMENT '소득수준',
    `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`member_id`),
    UNIQUE KEY `uk_member_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. WALLET (지갑 — 회원 1:1)
CREATE TABLE IF NOT EXISTS `wallet` (
    `wallet_id`       VARCHAR(36)    NOT NULL,
    `member_id`       VARCHAR(36)    NOT NULL,
    `total_balance`   DECIMAL(15,2)  NOT NULL DEFAULT 0.00,
    `created_at`      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`wallet_id`),
    UNIQUE KEY `uk_wallet_member` (`member_id`),
    CONSTRAINT `fk_wallet_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. PET (반려동물)
CREATE TABLE IF NOT EXISTS `pet` (
    `pet_id`          VARCHAR(36)   NOT NULL,
    `member_id`       VARCHAR(36)   NOT NULL,
    `name`            VARCHAR(30)   NOT NULL,
    `species`         VARCHAR(10)   NOT NULL COMMENT 'DOG / CAT / ETC',
    `breed`           VARCHAR(50)   NULL,
    `birth_date`      DATE          NULL,
    `gender`          VARCHAR(10)   NULL,
    `weight`          DECIMAL(5,2)  NULL,
    `neutered`        CHAR(1)       NULL COMMENT 'Y/N',
    `reg_number`      VARCHAR(30)   NULL COMMENT '동물등록번호',
    `icon_type`       VARCHAR(10)   NULL COMMENT 'DOG / CAT',
    `medical_history` CHAR(1)       NULL DEFAULT 'N' COMMENT 'Y/N',
    `profile_img`     VARCHAR(500)  NULL,
    `is_active`       TINYINT(1)    NOT NULL DEFAULT 1,
    `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`pet_id`),
    KEY `idx_pet_member` (`member_id`),
    CONSTRAINT `fk_pet_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. PET_DOCUMENT (반려동물 문서)
CREATE TABLE IF NOT EXISTS `pet_document` (
    `doc_id`       VARCHAR(36)   NOT NULL,
    `pet_id`       VARCHAR(36)   NOT NULL,
    `doc_type`     VARCHAR(20)   NOT NULL COMMENT 'REG_CERT / VACCINE / MEDICAL',
    `file_url`     VARCHAR(500)  NOT NULL,
    `issued_date`  DATE          NULL,
    `created_at`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`doc_id`),
    KEY `idx_petdoc_pet` (`pet_id`),
    CONSTRAINT `fk_petdoc_pet` FOREIGN KEY (`pet_id`) REFERENCES `pet`(`pet_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5. BUCKET (버킷 — 지갑 1:N)
CREATE TABLE IF NOT EXISTS `bucket` (
    `bucket_id`     VARCHAR(36)    NOT NULL,
    `wallet_id`     VARCHAR(36)    NOT NULL,
    `pet_id`        VARCHAR(36)    NULL COMMENT 'null이면 공통 지출',
    `bucket_type`   VARCHAR(20)    NOT NULL COMMENT 'HOSPITAL / FOOD / GROOMING / SOS / CUSTOM',
    `bucket_name`   VARCHAR(50)    NOT NULL,
    `target_amount` DECIMAL(15,2)  NULL DEFAULT 0.00,
    `balance`       DECIMAL(15,2)  NOT NULL DEFAULT 0.00,
    `is_sos`        TINYINT(1)     NOT NULL DEFAULT 0,
    `created_at`    DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`bucket_id`),
    KEY `idx_bucket_wallet` (`wallet_id`),
    KEY `idx_bucket_pet` (`pet_id`),
    CONSTRAINT `fk_bucket_wallet` FOREIGN KEY (`wallet_id`) REFERENCES `wallet`(`wallet_id`),
    CONSTRAINT `fk_bucket_pet` FOREIGN KEY (`pet_id`) REFERENCES `pet`(`pet_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 6. LINKED_ACCOUNT (연동 계좌)
CREATE TABLE IF NOT EXISTS `linked_account` (
    `account_id`     VARCHAR(36)   NOT NULL,
    `member_id`      VARCHAR(36)   NOT NULL,
    `bank_code`      VARCHAR(10)   NOT NULL,
    `bank_name`      VARCHAR(30)   NOT NULL,
    `account_number` VARCHAR(100)  NOT NULL COMMENT '암호화 저장',
    `account_holder` VARCHAR(30)   NOT NULL,
    `connected_id`   VARCHAR(100)  NULL COMMENT 'CODEF Connected ID',
    `is_primary`     TINYINT(1)    NOT NULL DEFAULT 0,
    `status`         VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / INACTIVE',
    `created_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`account_id`),
    KEY `idx_linked_member` (`member_id`),
    CONSTRAINT `fk_linked_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 7. TRANSACTION (거래/결제)
CREATE TABLE IF NOT EXISTS `transaction` (
    `txn_id`                  VARCHAR(36)    NOT NULL,
    `wallet_id`               VARCHAR(36)    NOT NULL,
    `bucket_id`               VARCHAR(36)    NULL COMMENT 'null이면 지갑 레벨 거래',
    `member_id`               VARCHAR(36)    NOT NULL,
    `txn_type`                VARCHAR(20)    NOT NULL COMMENT 'DEPOSIT / WITHDRAW / TRANSFER / PAYMENT',
    `amount`                  DECIMAL(15,2)  NOT NULL,
    `category`                VARCHAR(20)    NULL COMMENT 'HOSPITAL / FOOD / GROOMING / TOY / ETC',
    `merchant_name`           VARCHAR(100)   NULL,
    `merchant_category_code`  VARCHAR(20)    NULL,
    `memo`                    VARCHAR(200)   NULL,
    `auto_tagged`             CHAR(1)        NULL DEFAULT 'N' COMMENT 'Y/N',
    `txn_date`                DATETIME       NOT NULL,
    `created_at`              DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`txn_id`),
    KEY `idx_txn_wallet` (`wallet_id`),
    KEY `idx_txn_member` (`member_id`),
    KEY `idx_txn_date` (`txn_date`),
    CONSTRAINT `fk_txn_wallet` FOREIGN KEY (`wallet_id`) REFERENCES `wallet`(`wallet_id`),
    CONSTRAINT `fk_txn_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 8. RECURRING_PAYMENT (정기결제)
CREATE TABLE IF NOT EXISTS `recurring_payment` (
    `recurring_id`      VARCHAR(36)    NOT NULL,
    `wallet_id`         VARCHAR(36)    NOT NULL,
    `bucket_id`         VARCHAR(36)    NULL,
    `product_name`      VARCHAR(100)   NOT NULL,
    `amount`            DECIMAL(15,2)  NOT NULL,
    `cycle_days`        INT            NOT NULL COMMENT '주기 (일 단위)',
    `next_payment_date` DATE           NOT NULL,
    `is_active`         TINYINT(1)     NOT NULL DEFAULT 1,
    `created_at`        DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`recurring_id`),
    KEY `idx_recurring_wallet` (`wallet_id`),
    CONSTRAINT `fk_recurring_wallet` FOREIGN KEY (`wallet_id`) REFERENCES `wallet`(`wallet_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 9. INSURANCE_CLAIM (보험 청구)
CREATE TABLE IF NOT EXISTS `insurance_claim` (
    `claim_id`           VARCHAR(36)    NOT NULL,
    `pet_id`             VARCHAR(36)    NOT NULL,
    `member_id`          VARCHAR(36)    NOT NULL,
    `receipt_image_url`  VARCHAR(500)   NULL,
    `extracted_data`     JSON           NULL COMMENT 'Gemini Vision 추출 결과',
    `hospital_name`      VARCHAR(100)   NULL,
    `treatment_date`     DATE           NULL,
    `total_amount`       DECIMAL(15,2)  NULL,
    `claim_status`       VARCHAR(20)    NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / SUBMITTED / APPROVED / REJECTED',
    `claim_document_url` VARCHAR(500)   NULL,
    `created_at`         DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`         DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`claim_id`),
    KEY `idx_claim_pet` (`pet_id`),
    KEY `idx_claim_member` (`member_id`),
    CONSTRAINT `fk_claim_pet` FOREIGN KEY (`pet_id`) REFERENCES `pet`(`pet_id`),
    CONSTRAINT `fk_claim_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 10. INSURANCE_SIMULATION (보험 시뮬레이션)
CREATE TABLE IF NOT EXISTS `insurance_simulation` (
    `sim_id`                VARCHAR(36)    NOT NULL,
    `pet_id`                VARCHAR(36)    NOT NULL,
    `breed`                 VARCHAR(50)    NULL,
    `age`                   INT            NULL,
    `medical_history`       JSON           NULL,
    `estimated_annual_cost` DECIMAL(15,2)  NULL,
    `premium`               DECIMAL(15,2)  NULL,
    `deductible`            DECIMAL(15,2)  NULL,
    `break_even_year`       INT            NULL,
    `recommendation`        VARCHAR(20)    NULL COMMENT 'RECOMMENDED / NOT_RECOMMENDED / NEUTRAL',
    `created_at`            DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`sim_id`),
    KEY `idx_sim_pet` (`pet_id`),
    CONSTRAINT `fk_sim_pet` FOREIGN KEY (`pet_id`) REFERENCES `pet`(`pet_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 11. SHARED_ACCESS (공동 양육)
CREATE TABLE IF NOT EXISTS `shared_access` (
    `access_id`   VARCHAR(36)  NOT NULL,
    `wallet_id`   VARCHAR(36)  NOT NULL,
    `member_id`   VARCHAR(36)  NOT NULL COMMENT '초대받은 사용자',
    `invited_by`  VARCHAR(36)  NOT NULL COMMENT '초대한 사용자',
    `role`        VARCHAR(10)  NOT NULL DEFAULT 'VIEWER' COMMENT 'VIEWER / MANAGER / ADMIN',
    `status`      VARCHAR(10)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / ACCEPTED / REJECTED',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`access_id`),
    KEY `idx_shared_wallet` (`wallet_id`),
    CONSTRAINT `fk_shared_wallet` FOREIGN KEY (`wallet_id`) REFERENCES `wallet`(`wallet_id`),
    CONSTRAINT `fk_shared_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`),
    CONSTRAINT `fk_shared_inviter` FOREIGN KEY (`invited_by`) REFERENCES `member`(`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 12. ACTIVITY_LOG (활동 로그)
CREATE TABLE IF NOT EXISTS `activity_log` (
    `log_id`       VARCHAR(36)   NOT NULL,
    `wallet_id`    VARCHAR(36)   NOT NULL,
    `member_id`    VARCHAR(36)   NOT NULL,
    `action_type`  VARCHAR(20)   NOT NULL COMMENT 'CREATE / UPDATE / DELETE / PAYMENT / CLAIM',
    `target_type`  VARCHAR(20)   NOT NULL COMMENT 'BUCKET / TRANSACTION / CLAIM / PET',
    `target_id`    VARCHAR(36)   NULL,
    `description`  VARCHAR(300)  NULL,
    `created_at`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`log_id`),
    KEY `idx_log_wallet` (`wallet_id`),
    KEY `idx_log_member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 13. GROUP_PURCHASE (공동구매)
CREATE TABLE IF NOT EXISTS `group_purchase` (
    `gp_id`            VARCHAR(36)    NOT NULL,
    `author_id`        VARCHAR(36)    NOT NULL,
    `product_name`     VARCHAR(200)   NOT NULL,
    `product_url`      VARCHAR(500)   NULL,
    `unit_price`       DECIMAL(15,2)  NULL,
    `group_price`      DECIMAL(15,2)  NULL,
    `target_quantity`  INT            NOT NULL DEFAULT 1,
    `current_quantity` INT            NOT NULL DEFAULT 0,
    `status`           VARCHAR(20)    NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / CLOSED / COMPLETED / CANCELLED',
    `deadline`         DATETIME       NULL,
    `created_at`       DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`gp_id`),
    KEY `idx_gp_author` (`author_id`),
    CONSTRAINT `fk_gp_author` FOREIGN KEY (`author_id`) REFERENCES `member`(`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 14. GP_PARTICIPANT (공동구매 참여자)
CREATE TABLE IF NOT EXISTS `gp_participant` (
    `participant_id` VARCHAR(36)  NOT NULL,
    `gp_id`          VARCHAR(36)  NOT NULL,
    `member_id`      VARCHAR(36)  NOT NULL,
    `quantity`       INT          NOT NULL DEFAULT 1,
    `joined_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`participant_id`),
    KEY `idx_gpp_gp` (`gp_id`),
    CONSTRAINT `fk_gpp_gp` FOREIGN KEY (`gp_id`) REFERENCES `group_purchase`(`gp_id`),
    CONSTRAINT `fk_gpp_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 15. DONATION_POT (짜투리 저금통)
CREATE TABLE IF NOT EXISTS `donation_pot` (
    `pot_id`      VARCHAR(36)    NOT NULL,
    `member_id`   VARCHAR(36)    NOT NULL,
    `balance`     DECIMAL(15,2)  NOT NULL DEFAULT 0.00,
    `updated_at`  DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`pot_id`),
    UNIQUE KEY `uk_pot_member` (`member_id`),
    CONSTRAINT `fk_pot_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 16. DONATION_HISTORY (기부 이력)
CREATE TABLE IF NOT EXISTS `donation_history` (
    `donation_id`    VARCHAR(36)    NOT NULL,
    `pot_id`         VARCHAR(36)    NOT NULL,
    `amount`         DECIMAL(15,2)  NOT NULL,
    `recipient_name` VARCHAR(100)   NOT NULL,
    `donated_at`     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`donation_id`),
    KEY `idx_dh_pot` (`pot_id`),
    CONSTRAINT `fk_dh_pot` FOREIGN KEY (`pot_id`) REFERENCES `donation_pot`(`pot_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 17. LOCAL_SUPPORT_PROGRAM (지자체 지원사업)
CREATE TABLE IF NOT EXISTS `local_support_program` (
    `program_id`   VARCHAR(36)    NOT NULL,
    `region`       VARCHAR(50)    NOT NULL,
    `program_name` VARCHAR(200)   NOT NULL,
    `description`  TEXT           NULL,
    `eligibility`  JSON           NULL COMMENT '자격 조건',
    `apply_url`    VARCHAR(500)   NULL,
    `start_date`   DATE           NULL,
    `end_date`     DATE           NULL,
    `updated_at`   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`program_id`),
    KEY `idx_support_region` (`region`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 18. EMERGENCY_HOSPITAL (응급 동물병원)
CREATE TABLE IF NOT EXISTS `emergency_hospital` (
    `hospital_id`      VARCHAR(36)    NOT NULL,
    `name`             VARCHAR(100)   NOT NULL,
    `address`          VARCHAR(300)   NOT NULL,
    `phone`            VARCHAR(20)    NULL,
    `latitude`         DECIMAL(10,7)  NOT NULL,
    `longitude`        DECIMAL(10,7)  NOT NULL,
    `is_24h`           TINYINT(1)     NOT NULL DEFAULT 0,
    `is_holiday_open`  TINYINT(1)     NOT NULL DEFAULT 0,
    `avg_wait_minutes` INT            NULL,
    `updated_at`       DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`hospital_id`),
    KEY `idx_hospital_location` (`latitude`, `longitude`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 19. BREED_MEDICAL_STATS (품종별 의료 통계 — 시드 데이터)
CREATE TABLE IF NOT EXISTS `breed_medical_stats` (
    `stat_id`          VARCHAR(36)    NOT NULL,
    `species`          VARCHAR(10)    NOT NULL COMMENT 'DOG / CAT',
    `breed`            VARCHAR(50)    NOT NULL,
    `age_group`        VARCHAR(10)    NOT NULL COMMENT 'PUPPY / ADULT / SENIOR',
    `common_diseases`  JSON           NULL,
    `avg_annual_cost`  DECIMAL(15,2)  NULL,
    `source`           VARCHAR(100)   NULL,
    PRIMARY KEY (`stat_id`),
    KEY `idx_bms_breed` (`species`, `breed`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
