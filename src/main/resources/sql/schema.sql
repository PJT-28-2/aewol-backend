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
    `pet_id`                  VARCHAR(36)    NULL COMMENT '버킷과 무관한 반려동물 직접 태그',
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
    KEY `idx_txn_pet_date` (`pet_id`, `txn_date`),
    CONSTRAINT `fk_txn_wallet` FOREIGN KEY (`wallet_id`) REFERENCES `wallet`(`wallet_id`),
    CONSTRAINT `fk_txn_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`),
    CONSTRAINT `fk_txn_pet` FOREIGN KEY (`pet_id`) REFERENCES `pet`(`pet_id`) ON DELETE SET NULL
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
    `access_id`      VARCHAR(36)  NOT NULL,
    `wallet_id`      VARCHAR(36)  NOT NULL,
    `pet_id`         VARCHAR(36)  NULL COMMENT '기존 행은 NULL 허용, 신규 초대는 필수',
    `member_id`      VARCHAR(36)  NULL COMMENT '초대 수락 전 NULL, 수락 후 회원 ID',
    `invited_by`     VARCHAR(36)  NOT NULL COMMENT '초대한 사용자',
    `invite_code`    VARCHAR(36)  NULL,
    `recipient_type` VARCHAR(10)  NULL COMMENT 'EMAIL / PHONE / LINK',
    `recipient_value` VARCHAR(255) NULL COMMENT '정규화한 이메일 또는 전화번호',
    `role`           VARCHAR(10)  NOT NULL DEFAULT 'VIEWER' COMMENT 'VIEWER / MANAGER / ADMIN',
    `status`         VARCHAR(10)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / ACCEPTED / REJECTED / EXPIRED / REVOKED',
    `accepted_at`    DATETIME     NULL,
    `expires_at`     DATETIME     NULL,
    `revoked_at`     DATETIME     NULL,
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`access_id`),
    UNIQUE KEY `uk_shared_invite_code` (`invite_code`),
    UNIQUE KEY `uk_shared_pet_member` (`pet_id`, `member_id`),
    KEY `idx_shared_wallet` (`wallet_id`),
    KEY `idx_shared_pet_status` (`pet_id`, `status`),
    KEY `idx_shared_recipient_status` (`recipient_value`, `status`),
    CONSTRAINT `fk_shared_wallet` FOREIGN KEY (`wallet_id`) REFERENCES `wallet`(`wallet_id`),
    CONSTRAINT `fk_shared_pet` FOREIGN KEY (`pet_id`) REFERENCES `pet`(`pet_id`),
    CONSTRAINT `fk_shared_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`),
    CONSTRAINT `fk_shared_inviter` FOREIGN KEY (`invited_by`) REFERENCES `member`(`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 12. ACTIVITY_LOG (활동 로그)
CREATE TABLE IF NOT EXISTS `activity_log` (
    `log_id`       VARCHAR(36)   NOT NULL,
    `wallet_id`    VARCHAR(36)   NOT NULL,
    `member_id`    VARCHAR(36)   NOT NULL,
    `pet_id`       VARCHAR(36)   NULL,
    `action_type`  VARCHAR(20)   NOT NULL COMMENT 'CREATE / UPDATE / DELETE / PAYMENT / CLAIM',
    `target_type`  VARCHAR(20)   NOT NULL COMMENT 'BUCKET / TRANSACTION / CLAIM / PET',
    `target_id`    VARCHAR(36)   NULL,
    `title`        VARCHAR(200)  NULL COMMENT '활동 목록 제목',
    `description`  VARCHAR(300)  NULL,
    `metadata`     JSON          NULL COMMENT '화면 표시용 부가 정보',
    `created_at`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`log_id`),
    KEY `idx_log_wallet` (`wallet_id`),
    KEY `idx_log_member` (`member_id`),
    KEY `idx_activity_pet_created` (`pet_id`, `created_at`),
    CONSTRAINT `fk_activity_pet` FOREIGN KEY (`pet_id`) REFERENCES `pet`(`pet_id`) ON DELETE SET NULL
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

-- 16. DONATION_ORGANIZATION (기부처)
CREATE TABLE IF NOT EXISTS `donation_organization` (
    `organization_id`       VARCHAR(36)    NOT NULL,
    `name`                  VARCHAR(100)   NOT NULL,
    `description`           TEXT           NULL,
    `logo_url`              VARCHAR(500)   NULL,
    `homepage_url`          VARCHAR(500)   NULL,
    `region`                VARCHAR(100)   NULL,
    `category`              VARCHAR(30)    NULL,
    `target_species`        VARCHAR(20)    NOT NULL DEFAULT 'ALL' COMMENT 'DOG / CAT / ALL',
    `activity_tags`         JSON           NULL,
    `total_donation_amount` DECIMAL(15,2)  NOT NULL DEFAULT 0.00,
    `is_active`             TINYINT(1)     NOT NULL DEFAULT 1,
    `display_order`         INT            NOT NULL DEFAULT 0,
    `verified_at`           DATETIME       NULL,
    `created_at`            DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`organization_id`),
    KEY `idx_do_active_category` (`is_active`, `category`, `display_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 16-1. DONATION_CHANNEL (기부 경로)
CREATE TABLE IF NOT EXISTS `donation_channel` (
    `channel_id`      VARCHAR(36)   NOT NULL,
    `organization_id` VARCHAR(36)   NOT NULL,
    `channel_type`    VARCHAR(20)   NOT NULL COMMENT 'EXTERNAL_LINK / IN_APP',
    `donation_type`   VARCHAR(20)   NOT NULL COMMENT 'REGULAR / ONE_TIME / GOODS',
    `channel_name`    VARCHAR(100)  NOT NULL,
    `donation_url`    VARCHAR(1000) NULL,
    `is_active`       TINYINT(1)    NOT NULL DEFAULT 1,
    `display_order`   INT           NOT NULL DEFAULT 0,
    `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`channel_id`),
    KEY `idx_dc_organization_active` (`organization_id`, `is_active`, `display_order`),
    CONSTRAINT `fk_dc_organization` FOREIGN KEY (`organization_id`) REFERENCES `donation_organization`(`organization_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 16-2. DONATION_CAMPAIGN (기부 캠페인)
CREATE TABLE IF NOT EXISTS `donation_campaign` (
    `campaign_id`       VARCHAR(36)    NOT NULL,
    `organization_id`   VARCHAR(36)    NOT NULL,
    `channel_id`        VARCHAR(36)    NULL,
    `title`             VARCHAR(200)   NOT NULL,
    `category`          VARCHAR(30)    NULL,
    `description`       TEXT           NULL,
    `image_url`         VARCHAR(500)   NULL,
    `target_amount`     DECIMAL(15,2)  NULL,
    `raised_amount`     DECIMAL(15,2)  NOT NULL DEFAULT 0.00,
    `participant_count` INT            NOT NULL DEFAULT 0,
    `starts_at`         DATETIME       NULL,
    `ends_at`           DATETIME       NULL,
    `is_recommended`    TINYINT(1)     NOT NULL DEFAULT 0,
    `is_active`         TINYINT(1)     NOT NULL DEFAULT 1,
    `display_order`     INT            NOT NULL DEFAULT 0,
    `created_at`        DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`campaign_id`),
    KEY `idx_dcp_active_recommended` (`is_active`, `is_recommended`, `display_order`),
    KEY `idx_dcp_organization` (`organization_id`),
    CONSTRAINT `fk_dcp_organization` FOREIGN KEY (`organization_id`) REFERENCES `donation_organization`(`organization_id`),
    CONSTRAINT `fk_dcp_channel` FOREIGN KEY (`channel_id`) REFERENCES `donation_channel`(`channel_id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 16-3. DONATION_SETTING (저금통 설정)
CREATE TABLE IF NOT EXISTS `donation_setting` (
    `member_id`                    VARCHAR(36)    NOT NULL,
    `piggy_bank_enabled`           TINYINT(1)     NOT NULL DEFAULT 1,
    `saving_unit`                  DECIMAL(15,2)  NOT NULL DEFAULT 1000.00,
    `auto_donate_enabled`          TINYINT(1)     NOT NULL DEFAULT 0,
    `auto_donate_organization_id`  VARCHAR(36)    NULL,
    `auto_donate_campaign_id`      VARCHAR(36)    NULL,
    `last_auto_donated_year_month` CHAR(7)        NULL COMMENT 'YYYY-MM',
    `created_at`                   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`member_id`),
    CONSTRAINT `chk_ds_saving_unit` CHECK (`saving_unit` > 0),
    CONSTRAINT `fk_ds_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_ds_organization` FOREIGN KEY (`auto_donate_organization_id`) REFERENCES `donation_organization`(`organization_id`) ON DELETE SET NULL,
    CONSTRAINT `fk_ds_campaign` FOREIGN KEY (`auto_donate_campaign_id`) REFERENCES `donation_campaign`(`campaign_id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 16-4. MEMBER_DONATION_PREFERENCE (선호 기부처)
CREATE TABLE IF NOT EXISTS `member_donation_preference` (
    `member_id`       VARCHAR(36) NOT NULL,
    `organization_id` VARCHAR(36) NOT NULL,
    `created_at`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`member_id`, `organization_id`),
    CONSTRAINT `fk_mdp_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_mdp_organization` FOREIGN KEY (`organization_id`) REFERENCES `donation_organization`(`organization_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 16-5. DONATION_ROUNDUP (결제별 자투리 적립)
CREATE TABLE IF NOT EXISTS `donation_roundup` (
    `roundup_id`     VARCHAR(36)    NOT NULL,
    `source_txn_id`  VARCHAR(36)    NOT NULL,
    `pot_id`         VARCHAR(36)    NOT NULL,
    `saving_unit`    DECIMAL(15,2)  NOT NULL,
    `roundup_amount` DECIMAL(15,2)  NOT NULL,
    `status`         VARCHAR(20)    NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / COMPLETED / FAILED / SKIPPED',
    `failure_reason` VARCHAR(500)   NULL,
    `created_at`     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `completed_at`   DATETIME       NULL,
    PRIMARY KEY (`roundup_id`),
    UNIQUE KEY `uk_dr_source_txn` (`source_txn_id`),
    KEY `idx_dr_pot_status_created` (`pot_id`, `status`, `created_at`),
    CONSTRAINT `chk_dr_saving_unit` CHECK (`saving_unit` > 0),
    CONSTRAINT `chk_dr_roundup_amount` CHECK (`roundup_amount` >= 0),
    CONSTRAINT `fk_dr_source_txn` FOREIGN KEY (`source_txn_id`) REFERENCES `transaction`(`txn_id`),
    CONSTRAINT `fk_dr_pot` FOREIGN KEY (`pot_id`) REFERENCES `donation_pot`(`pot_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 16-6. DONATION_HISTORY (기부 이력)
CREATE TABLE IF NOT EXISTS `donation_history` (
    `donation_id`    VARCHAR(36)    NOT NULL,
    `pot_id`         VARCHAR(36)    NOT NULL,
    `organization_id` VARCHAR(36)   NULL,
    `campaign_id`    VARCHAR(36)    NULL,
    `channel_id`     VARCHAR(36)    NULL,
    `txn_id`         VARCHAR(36)    NULL,
    `amount`         DECIMAL(15,2)  NOT NULL,
    `status`         VARCHAR(20)    NOT NULL DEFAULT 'COMPLETED',
    `recipient_name` VARCHAR(100)   NOT NULL,
    `receipt_url`    VARCHAR(500)   NULL,
    `idempotency_key` VARCHAR(64)   NULL,
    `donated_at`     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `completed_at`   DATETIME       NULL,
    PRIMARY KEY (`donation_id`),
    UNIQUE KEY `uk_dh_idempotency_key` (`idempotency_key`),
    KEY `idx_dh_pot` (`pot_id`),
    KEY `idx_dh_organization` (`organization_id`),
    KEY `idx_dh_campaign` (`campaign_id`),
    CONSTRAINT `fk_dh_pot` FOREIGN KEY (`pot_id`) REFERENCES `donation_pot`(`pot_id`),
    CONSTRAINT `fk_dh_organization` FOREIGN KEY (`organization_id`) REFERENCES `donation_organization`(`organization_id`) ON DELETE SET NULL,
    CONSTRAINT `fk_dh_campaign` FOREIGN KEY (`campaign_id`) REFERENCES `donation_campaign`(`campaign_id`) ON DELETE SET NULL,
    CONSTRAINT `fk_dh_channel` FOREIGN KEY (`channel_id`) REFERENCES `donation_channel`(`channel_id`) ON DELETE SET NULL,
    CONSTRAINT `fk_dh_txn` FOREIGN KEY (`txn_id`) REFERENCES `transaction`(`txn_id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 17. GOV24_PUBLIC_SERVICE (정부24 공공서비스 원본)
CREATE TABLE IF NOT EXISTS `gov24_public_service` (
    `service_id`              VARCHAR(20)   NOT NULL,
    `support_type`            VARCHAR(100)  NULL,
    `service_name`            VARCHAR(300)  NOT NULL,
    `service_purpose_summary` TEXT          NULL,
    `support_target`          TEXT          NULL,
    `selection_criteria`      TEXT          NULL,
    `support_content`         TEXT          NULL,
    `application_method`      TEXT          NULL,
    `application_deadline`    VARCHAR(500)  NULL,
    `detail_url`              VARCHAR(1000) NULL,
    `organization_code`       VARCHAR(30)   NULL,
    `organization_name`       VARCHAR(200)  NULL,
    `department_name`         VARCHAR(200)  NULL,
    `view_count`              INT           NULL,
    `organization_type`       VARCHAR(100)  NULL,
    `user_type`               VARCHAR(100)  NULL,
    `service_category`        VARCHAR(100)  NULL,
    `reception_agency`        VARCHAR(200)  NULL,
    `contact_phone`           VARCHAR(200)  NULL,
    `source_registered_at`    DATETIME      NULL,
    `source_updated_at`       DATETIME      NULL,
    `synced_at`               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `created_at`              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`service_id`),
    KEY `idx_gov24_service_name` (`service_name`),
    KEY `idx_gov24_organization` (`organization_name`),
    KEY `idx_gov24_service_category` (`service_category`),
    KEY `idx_gov24_source_updated_at` (`source_updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 17-1. GOV24_PUBLIC_SERVICE_DETAIL (정부24 상세 원본)
CREATE TABLE IF NOT EXISTS `gov24_public_service_detail` (
    `service_id`                  VARCHAR(20)   NOT NULL,
    `service_purpose`             LONGTEXT      NULL,
    `required_documents`          LONGTEXT      NULL,
    `official_verified_documents` LONGTEXT      NULL,
    `identity_verified_documents` LONGTEXT      NULL,
    `reception_agency_name`       VARCHAR(300)  NULL,
    `contact_information`         LONGTEXT      NULL,
    `online_application_url`      VARCHAR(1000) NULL,
    `administrative_rules`        LONGTEXT      NULL,
    `local_regulations`           LONGTEXT      NULL,
    `laws`                        LONGTEXT      NULL,
    `source_updated_at`           DATETIME      NULL,
    `synced_at`                   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `created_at`                  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`service_id`),
    CONSTRAINT `fk_gov24_service_detail_service` FOREIGN KEY (`service_id`) REFERENCES `gov24_public_service`(`service_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 17-2. GOV24_PUBLIC_SERVICE_SUPPORT_CONDITION (정부24 조건 원본)
CREATE TABLE IF NOT EXISTS `gov24_public_service_support_condition` (
    `service_id`      VARCHAR(20)  NOT NULL,
    `condition_code`  VARCHAR(20)  NOT NULL,
    `condition_value` VARCHAR(255) NOT NULL,
    `synced_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`service_id`, `condition_code`),
    KEY `idx_gov24_condition_code_value` (`condition_code`, `condition_value`),
    CONSTRAINT `fk_gov24_service_condition_service` FOREIGN KEY (`service_id`) REFERENCES `gov24_public_service`(`service_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 17-3. LOCAL_SUPPORT_PROGRAM (서비스용 공공지원정책)
CREATE TABLE IF NOT EXISTS `local_support_program` (
    `program_id`        VARCHAR(36)   NOT NULL,
    `source_service_id` VARCHAR(20)   NULL,
    `source_type`       VARCHAR(20)   NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL / GOV24',
    `region`            VARCHAR(50)   NOT NULL,
    `program_name`      VARCHAR(200)  NOT NULL,
    `description`       TEXT          NULL,
    `summary`           VARCHAR(500)  NULL,
    `agency_name`       VARCHAR(200)  NULL,
    `benefit_summary`   VARCHAR(500)  NULL,
    `eligibility`       JSON          NULL,
    `target_species`    VARCHAR(20)   NOT NULL DEFAULT 'ALL' COMMENT 'DOG / CAT / ALL',
    `apply_url`         VARCHAR(500)  NULL,
    `period_text`       VARCHAR(500)  NULL,
    `application_method` VARCHAR(500) NULL,
    `start_date`        DATE          NULL,
    `end_date`          DATE          NULL,
    `is_active`         TINYINT(1)    NOT NULL DEFAULT 1,
    `source_updated_at` DATETIME      NULL,
    `synced_at`         DATETIME      NULL,
    `created_at`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`program_id`),
    UNIQUE KEY `uk_support_source_service` (`source_service_id`),
    KEY `idx_support_region` (`region`),
    KEY `idx_support_active_region` (`is_active`, `region`),
    KEY `idx_support_species_active` (`target_species`, `is_active`),
    CONSTRAINT `fk_support_source_service` FOREIGN KEY (`source_service_id`) REFERENCES `gov24_public_service`(`service_id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 17-4. LOCAL_SUPPORT_PROGRAM_CONDITION (정책 평가 조건)
CREATE TABLE IF NOT EXISTS `local_support_program_condition` (
    `program_condition_id` VARCHAR(36)   NOT NULL,
    `program_id`           VARCHAR(36)   NOT NULL,
    `condition_type`       VARCHAR(30)   NOT NULL,
    `operator`             VARCHAR(10)   NOT NULL DEFAULT 'EQ',
    `condition_value`      VARCHAR(500)  NULL,
    `title`                VARCHAR(200)  NOT NULL,
    `description`          VARCHAR(1000) NULL,
    `is_required`          TINYINT(1)    NOT NULL DEFAULT 1,
    `display_order`        INT           NOT NULL DEFAULT 0,
    `created_at`           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`program_condition_id`),
    KEY `idx_spc_program_order` (`program_id`, `display_order`),
    CONSTRAINT `fk_spc_program` FOREIGN KEY (`program_id`) REFERENCES `local_support_program`(`program_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 17-5. SUPPORT_PROGRAM_INTEREST (정책 관심·신청 상태)
CREATE TABLE IF NOT EXISTS `support_program_interest` (
    `interest_id` VARCHAR(36) NOT NULL,
    `member_id`   VARCHAR(36) NOT NULL,
    `program_id`  VARCHAR(36) NOT NULL,
    `pet_id`      VARCHAR(36) NULL,
    `status`      VARCHAR(30) NOT NULL DEFAULT 'INTERESTED',
    `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`interest_id`),
    UNIQUE KEY `uk_spi_member_program` (`member_id`, `program_id`),
    KEY `idx_spi_member_status` (`member_id`, `status`),
    CONSTRAINT `fk_spi_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_spi_program` FOREIGN KEY (`program_id`) REFERENCES `local_support_program`(`program_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_spi_pet` FOREIGN KEY (`pet_id`) REFERENCES `pet`(`pet_id`) ON DELETE SET NULL
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
