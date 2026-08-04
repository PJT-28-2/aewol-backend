-- 담당 영역: 공동육아, 공공지원정책, 사회적임팩트(기부)
-- 기준: src/main/resources/sql/schema.sql (Flyway V1은 설계 기준에서 제외)
-- 선행: 팀 개발 DB의 V3 적용 상태(member, wallet, pet, transaction 등 기존 서비스 테이블)
-- 개발/시연용 데이터는 이 마이그레이션에 포함하지 않는다.

-- ================================================================
-- 0. 행정안전부 공공서비스(혜택) API 원본 보관
-- ================================================================
CREATE TABLE IF NOT EXISTS `gov24_public_service` (
    `service_id`                 VARCHAR(20)   NOT NULL COMMENT '정부24 공공서비스 ID',
    `support_type`               VARCHAR(100)  NULL COMMENT '지원유형',
    `service_name`               VARCHAR(300)  NOT NULL COMMENT '서비스명',
    `service_purpose_summary`    TEXT          NULL COMMENT '서비스목적요약',
    `support_target`             TEXT          NULL COMMENT '지원대상',
    `selection_criteria`         TEXT          NULL COMMENT '선정기준',
    `support_content`            TEXT          NULL COMMENT '지원내용',
    `application_method`         TEXT          NULL COMMENT '신청방법',
    `application_deadline`       VARCHAR(500)  NULL COMMENT '신청기한 원문',
    `detail_url`                 VARCHAR(1000) NULL COMMENT '상세조회URL',
    `organization_code`          VARCHAR(30)   NULL COMMENT '소관기관코드',
    `organization_name`          VARCHAR(200)  NULL COMMENT '소관기관명',
    `department_name`            VARCHAR(200)  NULL COMMENT '부서명',
    `view_count`                 INT           NULL COMMENT '조회수',
    `organization_type`          VARCHAR(100)  NULL COMMENT '소관기관유형',
    `user_type`                  VARCHAR(100)  NULL COMMENT '사용자구분',
    `service_category`           VARCHAR(100)  NULL COMMENT '서비스분야',
    `reception_agency`           VARCHAR(200)  NULL COMMENT '접수기관',
    `contact_phone`              VARCHAR(200)  NULL COMMENT '전화문의',
    `source_registered_at`       DATETIME      NULL COMMENT '공공데이터 등록일시',
    `source_updated_at`          DATETIME      NULL COMMENT '공공데이터 수정일시',
    `synced_at`                  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '마지막 API 동기화 시각',
    `created_at`                 DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                 DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`service_id`),
    KEY `idx_gov24_service_name` (`service_name`),
    KEY `idx_gov24_organization` (`organization_name`),
    KEY `idx_gov24_service_category` (`service_category`),
    KEY `idx_gov24_source_updated_at` (`source_updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `gov24_public_service_detail` (
    `service_id`                        VARCHAR(20)   NOT NULL COMMENT '정부24 공공서비스 ID',
    `service_purpose`                   LONGTEXT      NULL COMMENT '서비스목적',
    `required_documents`                LONGTEXT      NULL COMMENT '구비서류',
    `official_verified_documents`       LONGTEXT      NULL COMMENT '공무원확인 구비서류',
    `identity_verified_documents`       LONGTEXT      NULL COMMENT '본인확인 필요 구비서류',
    `reception_agency_name`             VARCHAR(300)  NULL COMMENT '접수기관명',
    `contact_information`               LONGTEXT      NULL COMMENT '문의처',
    `online_application_url`            VARCHAR(1000) NULL COMMENT '온라인신청사이트URL',
    `administrative_rules`              LONGTEXT      NULL COMMENT '행정규칙',
    `local_regulations`                 LONGTEXT      NULL COMMENT '자치법규',
    `laws`                              LONGTEXT      NULL COMMENT '법령',
    `source_updated_at`                 DATETIME      NULL COMMENT '공공데이터 수정일시',
    `synced_at`                         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '마지막 API 동기화 시각',
    `created_at`                        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`service_id`),
    CONSTRAINT `fk_gov24_service_detail_service`
        FOREIGN KEY (`service_id`) REFERENCES `gov24_public_service` (`service_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `gov24_public_service_support_condition` (
    `service_id`       VARCHAR(20)  NOT NULL COMMENT '정부24 공공서비스 ID',
    `condition_code`   VARCHAR(20)  NOT NULL COMMENT '지원조건 코드',
    `condition_value`  VARCHAR(255) NOT NULL COMMENT '지원조건 값',
    `synced_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '마지막 API 동기화 시각',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`service_id`, `condition_code`),
    KEY `idx_gov24_condition_code_value` (`condition_code`, `condition_value`),
    CONSTRAINT `fk_gov24_service_condition_service`
        FOREIGN KEY (`service_id`) REFERENCES `gov24_public_service` (`service_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ================================================================
-- 1. 공동육아
-- ================================================================
CREATE TABLE `shared_access` (
    `access_id`       VARCHAR(36)  NOT NULL,
    `wallet_id`       VARCHAR(36)  NOT NULL,
    `pet_id`          VARCHAR(36)  NOT NULL,
    `member_id`       VARCHAR(36)  NULL COMMENT '초대 수락 전 NULL, 수락 후 회원 ID',
    `invited_by`      VARCHAR(36)  NOT NULL,
    `invite_code`     VARCHAR(36)  NOT NULL,
    `recipient_type`  VARCHAR(10)  NULL COMMENT 'EMAIL / PHONE / LINK',
    `recipient_value` VARCHAR(255) NULL COMMENT '정규화한 이메일 또는 전화번호',
    `role`            VARCHAR(10)  NOT NULL DEFAULT 'VIEWER' COMMENT 'VIEWER / MANAGER / ADMIN',
    `status`          VARCHAR(10)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / ACCEPTED / REJECTED / EXPIRED / REVOKED',
    `accepted_at`     DATETIME     NULL,
    `expires_at`      DATETIME     NULL,
    `revoked_at`      DATETIME     NULL,
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`access_id`),
    UNIQUE KEY `uk_shared_invite_code` (`invite_code`),
    UNIQUE KEY `uk_shared_pet_member` (`pet_id`, `member_id`),
    KEY `idx_shared_wallet` (`wallet_id`),
    KEY `idx_shared_pet_status` (`pet_id`, `status`),
    KEY `idx_shared_recipient_status` (`recipient_value`, `status`),
    CONSTRAINT `fk_shared_wallet` FOREIGN KEY (`wallet_id`) REFERENCES `wallet` (`wallet_id`),
    CONSTRAINT `fk_shared_pet` FOREIGN KEY (`pet_id`) REFERENCES `pet` (`pet_id`),
    CONSTRAINT `fk_shared_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`),
    CONSTRAINT `fk_shared_inviter` FOREIGN KEY (`invited_by`) REFERENCES `member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE `transaction`
    ADD KEY `idx_txn_pet_date` (`pet_id`, `txn_date`);

ALTER TABLE `activity_log`
    ADD COLUMN `pet_id` VARCHAR(36) NULL AFTER `member_id`,
    ADD COLUMN `title` VARCHAR(200) NULL COMMENT '활동 목록 제목' AFTER `target_id`,
    ADD COLUMN `metadata` JSON NULL COMMENT '화면 표시용 부가 정보' AFTER `description`,
    ADD KEY `idx_activity_pet_created` (`pet_id`, `created_at`),
    ADD CONSTRAINT `fk_activity_pet` FOREIGN KEY (`pet_id`) REFERENCES `pet` (`pet_id`) ON DELETE SET NULL;

-- ================================================================
-- 2. 공공지원정책
-- ================================================================
ALTER TABLE `local_support_program`
    ADD COLUMN `source_service_id` VARCHAR(20) NULL COMMENT 'GOV24 서비스 ID' AFTER `program_id`,
    ADD COLUMN `source_type` VARCHAR(20) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL / GOV24' AFTER `source_service_id`,
    ADD COLUMN `summary` VARCHAR(500) NULL COMMENT '목록용 요약' AFTER `description`,
    ADD COLUMN `agency_name` VARCHAR(200) NULL COMMENT '주관 기관명' AFTER `summary`,
    ADD COLUMN `benefit_summary` VARCHAR(500) NULL COMMENT '지원 혜택 요약' AFTER `agency_name`,
    ADD COLUMN `target_species` VARCHAR(20) NOT NULL DEFAULT 'ALL' COMMENT 'DOG / CAT / ALL' AFTER `eligibility`,
    ADD COLUMN `period_text` VARCHAR(500) NULL COMMENT '예산 소진 시까지 등 표시 문구' AFTER `apply_url`,
    ADD COLUMN `application_method` VARCHAR(500) NULL COMMENT '신청 방법' AFTER `period_text`,
    ADD COLUMN `is_active` TINYINT(1) NOT NULL DEFAULT 1 AFTER `end_date`,
    ADD COLUMN `source_updated_at` DATETIME NULL AFTER `is_active`,
    ADD COLUMN `synced_at` DATETIME NULL AFTER `source_updated_at`,
    ADD UNIQUE KEY `uk_support_source_service` (`source_service_id`),
    ADD KEY `idx_support_active_region` (`is_active`, `region`),
    ADD KEY `idx_support_species_active` (`target_species`, `is_active`),
    ADD CONSTRAINT `fk_support_source_service`
        FOREIGN KEY (`source_service_id`) REFERENCES `gov24_public_service` (`service_id`) ON DELETE SET NULL;

CREATE TABLE `local_support_program_condition` (
    `program_condition_id` VARCHAR(36) NOT NULL,
    `program_id`           VARCHAR(36) NOT NULL,
    `condition_type`       VARCHAR(30) NOT NULL COMMENT 'REGION / PET_SPECIES / PET_AGE_MONTHS / NEUTERED / PET_REGISTERED / MANUAL',
    `operator`             VARCHAR(10) NOT NULL DEFAULT 'EQ' COMMENT 'EQ / IN / LTE / GTE / BETWEEN / MANUAL',
    `condition_value`      VARCHAR(500) NULL,
    `title`                VARCHAR(200) NOT NULL,
    `description`          VARCHAR(1000) NULL,
    `is_required`          TINYINT(1) NOT NULL DEFAULT 1,
    `display_order`        INT NOT NULL DEFAULT 0,
    `created_at`           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`program_condition_id`),
    KEY `idx_spc_program_order` (`program_id`, `display_order`),
    CONSTRAINT `fk_spc_program` FOREIGN KEY (`program_id`) REFERENCES `local_support_program` (`program_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `support_program_interest` (
    `interest_id` VARCHAR(36) NOT NULL,
    `member_id`   VARCHAR(36) NOT NULL,
    `program_id`  VARCHAR(36) NOT NULL,
    `pet_id`      VARCHAR(36) NULL,
    `status`      VARCHAR(30) NOT NULL DEFAULT 'INTERESTED' COMMENT 'INTERESTED / APPLY_PAGE_OPENED / DISMISSED',
    `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`interest_id`),
    UNIQUE KEY `uk_spi_member_program` (`member_id`, `program_id`),
    KEY `idx_spi_member_status` (`member_id`, `status`),
    CONSTRAINT `fk_spi_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_spi_program` FOREIGN KEY (`program_id`) REFERENCES `local_support_program` (`program_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_spi_pet` FOREIGN KEY (`pet_id`) REFERENCES `pet` (`pet_id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ================================================================
-- 3. 사회적임팩트(기부)
-- ================================================================
ALTER TABLE `donation_organization`
    ADD COLUMN `homepage_url` VARCHAR(500) NULL AFTER `logo_url`,
    ADD COLUMN `region` VARCHAR(100) NULL AFTER `homepage_url`,
    ADD COLUMN `target_species` VARCHAR(20) NOT NULL DEFAULT 'ALL' COMMENT 'DOG / CAT / ALL' AFTER `category`,
    ADD COLUMN `activity_tags` JSON NULL AFTER `target_species`,
    ADD COLUMN `is_active` TINYINT(1) NOT NULL DEFAULT 1 AFTER `total_donation_amount`,
    ADD COLUMN `display_order` INT NOT NULL DEFAULT 0 AFTER `is_active`,
    ADD COLUMN `verified_at` DATETIME NULL AFTER `display_order`,
    ADD COLUMN `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER `created_at`,
    ADD KEY `idx_do_active_category` (`is_active`, `category`, `display_order`);

CREATE TABLE `donation_channel` (
    `channel_id`      VARCHAR(36) NOT NULL,
    `organization_id` VARCHAR(36) NOT NULL,
    `channel_type`    VARCHAR(20) NOT NULL COMMENT 'EXTERNAL_LINK / IN_APP',
    `donation_type`   VARCHAR(20) NOT NULL COMMENT 'REGULAR / ONE_TIME / GOODS',
    `channel_name`    VARCHAR(100) NOT NULL,
    `donation_url`    VARCHAR(1000) NULL,
    `is_active`       TINYINT(1) NOT NULL DEFAULT 1,
    `display_order`   INT NOT NULL DEFAULT 0,
    `created_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`channel_id`),
    KEY `idx_dc_organization_active` (`organization_id`, `is_active`, `display_order`),
    CONSTRAINT `fk_dc_organization` FOREIGN KEY (`organization_id`) REFERENCES `donation_organization` (`organization_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `donation_campaign` (
    `campaign_id`       VARCHAR(36) NOT NULL,
    `organization_id`   VARCHAR(36) NOT NULL,
    `channel_id`        VARCHAR(36) NULL,
    `title`             VARCHAR(200) NOT NULL,
    `category`          VARCHAR(30) NULL,
    `description`       TEXT NULL,
    `image_url`         VARCHAR(500) NULL,
    `target_amount`     DECIMAL(15,2) NULL,
    `raised_amount`     DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    `participant_count` INT NOT NULL DEFAULT 0,
    `starts_at`         DATETIME NULL,
    `ends_at`           DATETIME NULL,
    `is_recommended`    TINYINT(1) NOT NULL DEFAULT 0,
    `is_active`         TINYINT(1) NOT NULL DEFAULT 1,
    `display_order`     INT NOT NULL DEFAULT 0,
    `created_at`        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`campaign_id`),
    KEY `idx_dcp_active_recommended` (`is_active`, `is_recommended`, `display_order`),
    KEY `idx_dcp_organization` (`organization_id`),
    CONSTRAINT `fk_dcp_organization` FOREIGN KEY (`organization_id`) REFERENCES `donation_organization` (`organization_id`),
    CONSTRAINT `fk_dcp_channel` FOREIGN KEY (`channel_id`) REFERENCES `donation_channel` (`channel_id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `donation_setting` (
    `member_id`                    VARCHAR(36) NOT NULL,
    `piggy_bank_enabled`           TINYINT(1) NOT NULL DEFAULT 1,
    `saving_unit`                  DECIMAL(15,2) NOT NULL DEFAULT 1000.00,
    `auto_donate_enabled`          TINYINT(1) NOT NULL DEFAULT 0,
    `auto_donate_organization_id`  VARCHAR(36) NULL,
    `auto_donate_campaign_id`      VARCHAR(36) NULL,
    `last_auto_donated_year_month` CHAR(7) NULL COMMENT 'YYYY-MM',
    `created_at`                   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`member_id`),
    CONSTRAINT `chk_ds_saving_unit` CHECK (`saving_unit` > 0),
    CONSTRAINT `fk_ds_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_ds_organization` FOREIGN KEY (`auto_donate_organization_id`) REFERENCES `donation_organization` (`organization_id`) ON DELETE SET NULL,
    CONSTRAINT `fk_ds_campaign` FOREIGN KEY (`auto_donate_campaign_id`) REFERENCES `donation_campaign` (`campaign_id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `member_donation_preference` (
    `member_id`       VARCHAR(36) NOT NULL,
    `organization_id` VARCHAR(36) NOT NULL,
    `created_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`member_id`, `organization_id`),
    CONSTRAINT `fk_mdp_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_mdp_organization` FOREIGN KEY (`organization_id`) REFERENCES `donation_organization` (`organization_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `donation_roundup` (
    `roundup_id`     VARCHAR(36) NOT NULL,
    `source_txn_id`  VARCHAR(36) NOT NULL,
    `pot_id`         VARCHAR(36) NOT NULL,
    `saving_unit`    DECIMAL(15,2) NOT NULL,
    `roundup_amount` DECIMAL(15,2) NOT NULL,
    `status`         VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / COMPLETED / FAILED / SKIPPED',
    `failure_reason` VARCHAR(500) NULL,
    `created_at`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `completed_at`   DATETIME NULL,
    PRIMARY KEY (`roundup_id`),
    UNIQUE KEY `uk_dr_source_txn` (`source_txn_id`),
    KEY `idx_dr_pot_status_created` (`pot_id`, `status`, `created_at`),
    CONSTRAINT `chk_dr_saving_unit` CHECK (`saving_unit` > 0),
    CONSTRAINT `chk_dr_roundup_amount` CHECK (`roundup_amount` >= 0),
    CONSTRAINT `fk_dr_source_txn` FOREIGN KEY (`source_txn_id`) REFERENCES `transaction` (`txn_id`),
    CONSTRAINT `fk_dr_pot` FOREIGN KEY (`pot_id`) REFERENCES `donation_pot` (`pot_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE `donation_history`
    DROP FOREIGN KEY `fk_dh_organization`;

ALTER TABLE `donation_history`
    MODIFY COLUMN `organization_id` VARCHAR(36) NULL,
    ADD COLUMN `campaign_id` VARCHAR(36) NULL AFTER `organization_id`,
    ADD COLUMN `channel_id` VARCHAR(36) NULL AFTER `campaign_id`,
    ADD COLUMN `txn_id` VARCHAR(36) NULL AFTER `channel_id`,
    ADD COLUMN `recipient_name` VARCHAR(100) NULL AFTER `status`,
    ADD COLUMN `idempotency_key` VARCHAR(64) NULL AFTER `receipt_url`,
    ADD COLUMN `completed_at` DATETIME NULL AFTER `donated_at`,
    ADD UNIQUE KEY `uk_dh_idempotency_key` (`idempotency_key`),
    ADD KEY `idx_dh_campaign` (`campaign_id`),
    ADD CONSTRAINT `fk_dh_organization` FOREIGN KEY (`organization_id`) REFERENCES `donation_organization` (`organization_id`) ON DELETE SET NULL,
    ADD CONSTRAINT `fk_dh_campaign` FOREIGN KEY (`campaign_id`) REFERENCES `donation_campaign` (`campaign_id`) ON DELETE SET NULL,
    ADD CONSTRAINT `fk_dh_channel` FOREIGN KEY (`channel_id`) REFERENCES `donation_channel` (`channel_id`) ON DELETE SET NULL,
    ADD CONSTRAINT `fk_dh_txn` FOREIGN KEY (`txn_id`) REFERENCES `transaction` (`txn_id`) ON DELETE SET NULL;
