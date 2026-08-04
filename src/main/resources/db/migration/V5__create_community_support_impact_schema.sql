-- =====================================================================
-- V5: 공동육아(RF-CO) / 공공지원정책(RF-PA) / 사회적임팩트-기부(RF-SI)
--
-- 선행: V1~V4 적용 상태.
--   - V3에서 전체 서로게이트 PK가 BIGINT AUTO_INCREMENT로 전환되었으므로
--     이 마이그레이션의 모든 PK/FK도 BIGINT를 사용한다.
--   - 자연키(bank_master.bank_code, account_verification.transaction_id)와
--     동일한 이유로 정부24 service_id는 VARCHAR(20) 원본 키를 그대로 쓴다.
--
-- 기부함(저금통) 저장소에 대하여:
--   구 설계(src/main/resources/sql/schema.sql)에는 별도 `donation_pot`
--   테이블이 있었으나, V1에서 "회원당 MAIN(실지갑)/DONATION(기부함) 타입별
--   최대 1개"로 지갑이 통합되면서 제거되었다. 잔액 소스가 둘로 갈라지는 것을
--   막기 위해 이 마이그레이션은 donation_pot을 되살리지 않고
--   `wallet`(wallet_type='DONATION')을 기부함으로 사용한다.
--   따라서 적립/기부 내역은 pot_id가 아니라 wallet_id로 연결한다.
--
-- 프론트엔드 계약(aewol-frontend) 기준:
--   GET  /api/share/pets, /api/share/{petId}/members, /share/contributions,
--        /share/logs, /share/invites/{code}
--   POST /api/share/invite, /share/invite/link, /share/invites/{code}/accept
--   GET  /api/support/matched?petId=  · POST /api/support/{programId}/interest
--   GET  /api/donation · PUT /api/donation/settings · POST /api/donation
--   POST /api/donation/pot/withdraw
-- =====================================================================

-- =====================================================================
-- 0. member 주소 정책 반영 (팀 ERD 확정)
--    - region 컬럼 삭제는 작업 없음: V1 신 스키마에 애초에 존재하지 않는다
--      (구 계보 스키마에만 있었음). 지역 조건 평가는 address 접두 비교를 쓴다.
--    - UNIQUE(provider, provider_id)는 V4 uk_member_provider로 이미 반영됨.
--    - zip_code / address 필수화, address_detail 길이 100 -> 300 확장.
-- =====================================================================
ALTER TABLE `member`
    MODIFY COLUMN `zip_code` VARCHAR(10) NOT NULL,
    MODIFY COLUMN `address` VARCHAR(300) NOT NULL,
    MODIFY COLUMN `address_detail` VARCHAR(300) NULL COMMENT '상세주소';

-- =====================================================================
-- 1. 행정안전부 공공서비스(혜택) API 원본 보관
--    외부 API 원본을 그대로 적재해 두고, 서비스용 가공 결과는
--    local_support_program으로 옮긴다 (source_service_id로 연결).
-- =====================================================================
CREATE TABLE IF NOT EXISTS `gov24_public_service` (
    `service_id`                 VARCHAR(20)   NOT NULL COMMENT '정부24 공공서비스 ID (외부 자연키)',
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

-- =====================================================================
-- 2. 공동육아 (RF-CO)
-- =====================================================================
-- V1의 `pet_group_member`와 목적이 겹치지만, 프론트 초대 플로우가 요구하는
-- 수신자 유형(EMAIL/PHONE/LINK), 만료(expires_at), 회수(revoked_at),
-- 지갑 연결(wallet_id)이 pet_group_member에는 없어 별도 테이블로 둔다.
-- 공동육아 도메인은 `shared_access`만 사용하며 pet_group_member는 참조하지 않는다.
CREATE TABLE IF NOT EXISTS `shared_access` (
    `access_id`       BIGINT       NOT NULL AUTO_INCREMENT,
    `wallet_id`       BIGINT       NOT NULL COMMENT '공동육아 대상 지갑 (소유자의 MAIN 지갑)',
    `pet_id`          BIGINT       NOT NULL,
    `member_id`       BIGINT       NULL COMMENT '초대 수락 전 NULL, 수락 후 회원 ID',
    `invited_by`      BIGINT       NOT NULL,
    `invite_code`     VARCHAR(36)  NOT NULL COMMENT '초대 링크/코드 (앱 생성 UUID)',
    `recipient_type`  VARCHAR(10)  NULL COMMENT 'EMAIL / PHONE / LINK',
    `recipient_value` VARCHAR(255) NULL COMMENT '정규화한 이메일 또는 전화번호. LINK 초대는 NULL',
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

-- 기여도 집계(GET /api/share/contributions)가 반려동물 + 월 범위로 조회한다.
ALTER TABLE `transaction`
    ADD KEY `idx_txn_pet_date` (`pet_id`, `txn_date`);

-- 공동육아 활동 로그(GET /api/share/logs)는 반려동물 단위로 조회하고
-- 목록에 제목을 노출한다. V1의 activity_log에는 pet_id/title/metadata가 없다.
-- (V1은 activity_log에 member_id를 두지 않으므로 pet_id를 wallet_id 뒤에 붙인다.)
ALTER TABLE `activity_log`
    ADD COLUMN `pet_id` BIGINT NULL AFTER `wallet_id`,
    ADD COLUMN `title` VARCHAR(200) NULL COMMENT '활동 목록 제목' AFTER `target_id`,
    ADD COLUMN `metadata` JSON NULL COMMENT '화면 표시용 부가 정보' AFTER `description`,
    ADD KEY `idx_activity_pet_created` (`pet_id`, `created_at`),
    ADD CONSTRAINT `fk_activity_pet` FOREIGN KEY (`pet_id`) REFERENCES `pet` (`pet_id`) ON DELETE SET NULL;

-- =====================================================================
-- 3. 공공지원정책 (RF-PA)
-- =====================================================================
-- 프론트 SupportProgramsView가 쓰는 필드: title / summary / agency / benefit
-- / period / eligible / conditions[{met, title, description}]
ALTER TABLE `local_support_program`
    ADD COLUMN `source_service_id` VARCHAR(20) NULL COMMENT 'GOV24 서비스 ID' AFTER `program_id`,
    ADD COLUMN `source_type` VARCHAR(20) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL / GOV24' AFTER `source_service_id`,
    ADD COLUMN `summary` VARCHAR(500) NULL COMMENT '목록용 요약' AFTER `description`,
    ADD COLUMN `agency_name` VARCHAR(200) NULL COMMENT '주관 기관명' AFTER `summary`,
    ADD COLUMN `benefit_summary` VARCHAR(500) NULL COMMENT '지원 혜택 요약' AFTER `agency_name`,
    ADD COLUMN `target_species` VARCHAR(20) NOT NULL DEFAULT 'ALL' COMMENT 'DOG / CAT / ALL' AFTER `eligibility`,
    ADD COLUMN `period_text` VARCHAR(500) NULL COMMENT '"예산 소진 시까지" 등 표시 문구' AFTER `apply_url`,
    ADD COLUMN `application_method` VARCHAR(500) NULL COMMENT '신청 방법' AFTER `period_text`,
    ADD COLUMN `is_active` TINYINT(1) NOT NULL DEFAULT 1 AFTER `end_date`,
    ADD COLUMN `source_updated_at` DATETIME NULL AFTER `is_active`,
    ADD COLUMN `synced_at` DATETIME NULL AFTER `source_updated_at`,
    ADD UNIQUE KEY `uk_support_source_service` (`source_service_id`),
    ADD KEY `idx_support_active_region` (`is_active`, `region`),
    ADD KEY `idx_support_species_active` (`target_species`, `is_active`),
    ADD CONSTRAINT `fk_support_source_service`
        FOREIGN KEY (`source_service_id`) REFERENCES `gov24_public_service` (`service_id`) ON DELETE SET NULL;

-- 조건 충족 여부(conditions[].met)는 저장하지 않고 조회 시점에 회원/반려동물
-- 정보로 평가한다. 이 테이블은 "평가 규칙"과 화면 표시 문구만 담는다.
CREATE TABLE IF NOT EXISTS `local_support_program_condition` (
    `program_condition_id` BIGINT        NOT NULL AUTO_INCREMENT,
    `program_id`           BIGINT        NOT NULL,
    `condition_type`       VARCHAR(30)   NOT NULL COMMENT 'REGION / PET_SPECIES / PET_AGE_MONTHS / NEUTERED / PET_REGISTERED / MANUAL',
    `operator`             VARCHAR(10)   NOT NULL DEFAULT 'EQ' COMMENT 'EQ / IN / LTE / GTE / BETWEEN / MANUAL',
    `condition_value`      VARCHAR(500)  NULL,
    `title`                VARCHAR(200)  NOT NULL COMMENT '화면 표시용 조건명',
    `description`          VARCHAR(1000) NULL COMMENT '화면 표시용 설명',
    `is_required`          TINYINT(1)    NOT NULL DEFAULT 1,
    `display_order`        INT           NOT NULL DEFAULT 0,
    `created_at`           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`program_condition_id`),
    KEY `idx_spc_program_order` (`program_id`, `display_order`),
    CONSTRAINT `fk_spc_program` FOREIGN KEY (`program_id`) REFERENCES `local_support_program` (`program_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- POST /api/support/{programId}/interest — 실제 신청은 외부 사이트에서
-- 이루어지므로 "신청 페이지를 열었다"는 사실만 기록한다 (프론트 applied 플래그).
CREATE TABLE IF NOT EXISTS `support_program_interest` (
    `interest_id` BIGINT      NOT NULL AUTO_INCREMENT,
    `member_id`   BIGINT      NOT NULL,
    `program_id`  BIGINT      NOT NULL,
    `pet_id`      BIGINT      NULL,
    `status`      VARCHAR(30) NOT NULL DEFAULT 'INTERESTED' COMMENT 'INTERESTED / APPLY_PAGE_OPENED / DISMISSED',
    `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`interest_id`),
    UNIQUE KEY `uk_spi_member_program` (`member_id`, `program_id`),
    KEY `idx_spi_member_status` (`member_id`, `status`),
    CONSTRAINT `fk_spi_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_spi_program` FOREIGN KEY (`program_id`) REFERENCES `local_support_program` (`program_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_spi_pet` FOREIGN KEY (`pet_id`) REFERENCES `pet` (`pet_id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================================
-- 4. 사회적임팩트 — 짜투리 저금통 + 기부 (RF-SI)
-- =====================================================================
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

CREATE TABLE IF NOT EXISTS `donation_channel` (
    `channel_id`      BIGINT       NOT NULL AUTO_INCREMENT,
    `organization_id` BIGINT       NOT NULL,
    `channel_type`    VARCHAR(20)  NOT NULL COMMENT 'EXTERNAL_LINK / IN_APP',
    `donation_type`   VARCHAR(20)  NOT NULL COMMENT 'REGULAR / ONE_TIME / GOODS',
    `channel_name`    VARCHAR(100) NOT NULL,
    `donation_url`    VARCHAR(1000) NULL COMMENT 'EXTERNAL_LINK일 때 외부 기부 페이지',
    `is_active`       TINYINT(1)   NOT NULL DEFAULT 1,
    `display_order`   INT          NOT NULL DEFAULT 0,
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`channel_id`),
    KEY `idx_dc_organization_active` (`organization_id`, `is_active`, `display_order`),
    CONSTRAINT `fk_dc_organization` FOREIGN KEY (`organization_id`) REFERENCES `donation_organization` (`organization_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 프론트 DonationView가 쓰는 필드: id / title / organization / progress
-- (progress는 raised_amount / target_amount로 서버에서 계산해 내려준다)
CREATE TABLE IF NOT EXISTS `donation_campaign` (
    `campaign_id`       BIGINT        NOT NULL AUTO_INCREMENT,
    `organization_id`   BIGINT        NOT NULL,
    `channel_id`        BIGINT        NULL,
    `title`             VARCHAR(200)  NOT NULL,
    `category`          VARCHAR(30)   NULL COMMENT '유기동물 / 환경 / 기타',
    `description`       TEXT          NULL,
    `image_url`         VARCHAR(500)  NULL,
    `target_amount`     DECIMAL(15,2) NULL,
    `raised_amount`     DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    `participant_count` INT           NOT NULL DEFAULT 0,
    `starts_at`         DATETIME      NULL,
    `ends_at`           DATETIME      NULL,
    `is_recommended`    TINYINT(1)    NOT NULL DEFAULT 0,
    `is_active`         TINYINT(1)    NOT NULL DEFAULT 1,
    `display_order`     INT           NOT NULL DEFAULT 0,
    `created_at`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`campaign_id`),
    KEY `idx_dcp_active_recommended` (`is_active`, `is_recommended`, `display_order`),
    KEY `idx_dcp_organization` (`organization_id`),
    CONSTRAINT `fk_dcp_organization` FOREIGN KEY (`organization_id`) REFERENCES `donation_organization` (`organization_id`),
    CONSTRAINT `fk_dcp_channel` FOREIGN KEY (`channel_id`) REFERENCES `donation_channel` (`channel_id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- PUT /api/donation/settings — 프론트가 보내는 값:
--   piggyBankEnabled / savingUnit(10·100·1000) / autoDonate / campaignId
CREATE TABLE IF NOT EXISTS `donation_setting` (
    `member_id`                    BIGINT        NOT NULL,
    `piggy_bank_enabled`           TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '잔돈 적립 사용 여부',
    `saving_unit`                  DECIMAL(15,2) NOT NULL DEFAULT 1000.00 COMMENT '올림 단위 (10 / 100 / 1000)',
    `auto_donate_enabled`          TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '월말 자동 기부',
    `auto_donate_organization_id`  BIGINT        NULL,
    `auto_donate_campaign_id`      BIGINT        NULL,
    `last_auto_donated_year_month` CHAR(7)       NULL COMMENT 'YYYY-MM. 월 1회 배치 멱등성 보장용',
    `created_at`                   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`member_id`),
    CONSTRAINT `chk_ds_saving_unit` CHECK (`saving_unit` > 0),
    CONSTRAINT `fk_ds_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_ds_organization` FOREIGN KEY (`auto_donate_organization_id`) REFERENCES `donation_organization` (`organization_id`) ON DELETE SET NULL,
    CONSTRAINT `fk_ds_campaign` FOREIGN KEY (`auto_donate_campaign_id`) REFERENCES `donation_campaign` (`campaign_id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 캠페인 목록 정렬 시 "관심 단체" 가산점(preferred 플래그)에 사용한다.
CREATE TABLE IF NOT EXISTS `member_donation_preference` (
    `member_id`       BIGINT   NOT NULL,
    `organization_id` BIGINT   NOT NULL,
    `created_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`member_id`, `organization_id`),
    CONSTRAINT `fk_mdp_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_mdp_organization` FOREIGN KEY (`organization_id`) REFERENCES `donation_organization` (`organization_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 잔돈 적립 이력. wallet_id는 적립 대상 DONATION 지갑(기부함)이다.
-- uk_dr_source_txn으로 같은 결제건이 두 번 적립되지 않도록 배치 멱등성을 보장한다.
CREATE TABLE IF NOT EXISTS `donation_roundup` (
    `roundup_id`     BIGINT        NOT NULL AUTO_INCREMENT,
    `source_txn_id`  BIGINT        NOT NULL COMMENT '적립을 발생시킨 원본 결제 거래',
    `wallet_id`      BIGINT        NOT NULL COMMENT '적립 대상 DONATION 지갑',
    `saving_unit`    DECIMAL(15,2) NOT NULL,
    `roundup_amount` DECIMAL(15,2) NOT NULL,
    `status`         VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / COMPLETED / FAILED / SKIPPED',
    `failure_reason` VARCHAR(500)  NULL,
    `created_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `completed_at`   DATETIME      NULL,
    PRIMARY KEY (`roundup_id`),
    UNIQUE KEY `uk_dr_source_txn` (`source_txn_id`),
    KEY `idx_dr_wallet_status_created` (`wallet_id`, `status`, `created_at`),
    CONSTRAINT `chk_dr_saving_unit` CHECK (`saving_unit` > 0),
    CONSTRAINT `chk_dr_roundup_amount` CHECK (`roundup_amount` >= 0),
    CONSTRAINT `fk_dr_source_txn` FOREIGN KEY (`source_txn_id`) REFERENCES `transaction` (`txn_id`),
    CONSTRAINT `fk_dr_wallet` FOREIGN KEY (`wallet_id`) REFERENCES `wallet` (`wallet_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- donation_history 확장
--   V1 기준 컬럼: donation_id, wallet_id, organization_id, txn_id, amount,
--                 status, receipt_url, created_at, completed_at
--   추가: campaign_id / channel_id / recipient_name / idempotency_key
--   변경: organization_id를 NULL 허용으로 완화한다. 캠페인 단위 기부에서는
--         단체가 campaign -> organization으로 유도되고, 단체가 삭제돼도
--         기부 이력은 남아야 하므로 ON DELETE SET NULL로 재생성한다.
--   제거: chk_dh_status_txn. 이 CHECK는 status='COMPLETED'인 행에 txn_id가
--         반드시 있어야 한다고 강제하는데, 외부 링크(EXTERNAL_LINK) 채널
--         기부는 앱 내부 자금 이동(transaction)이 발생하지 않는다.
--         멱등성은 uk_dh_idempotency_key가 대신 보장한다.
-- ---------------------------------------------------------------------
ALTER TABLE `donation_history`
    DROP CHECK `chk_dh_status_txn`;

ALTER TABLE `donation_history`
    DROP FOREIGN KEY `fk_dh_organization`;

ALTER TABLE `donation_history`
    MODIFY COLUMN `organization_id` BIGINT NULL,
    ADD COLUMN `campaign_id` BIGINT NULL AFTER `organization_id`,
    ADD COLUMN `channel_id` BIGINT NULL AFTER `campaign_id`,
    ADD COLUMN `recipient_name` VARCHAR(100) NULL COMMENT '기부처 표시명 스냅샷' AFTER `status`,
    ADD COLUMN `idempotency_key` VARCHAR(64) NULL COMMENT '클라이언트 생성 키. 중복 기부 방지' AFTER `receipt_url`,
    ADD UNIQUE KEY `uk_dh_idempotency_key` (`idempotency_key`),
    ADD KEY `idx_dh_campaign` (`campaign_id`),
    ADD CONSTRAINT `fk_dh_organization` FOREIGN KEY (`organization_id`) REFERENCES `donation_organization` (`organization_id`) ON DELETE SET NULL,
    ADD CONSTRAINT `fk_dh_campaign` FOREIGN KEY (`campaign_id`) REFERENCES `donation_campaign` (`campaign_id`) ON DELETE SET NULL,
    ADD CONSTRAINT `fk_dh_channel` FOREIGN KEY (`channel_id`) REFERENCES `donation_channel` (`channel_id`) ON DELETE SET NULL;
