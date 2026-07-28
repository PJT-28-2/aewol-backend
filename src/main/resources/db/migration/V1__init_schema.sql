-- =====================================================================
-- 애월(AeWol) 초기 스키마
-- 테이블은 FK 참조 방향에 따라 순서대로 정의됨 (정방향 참조 없음)
-- =====================================================================

CREATE TABLE IF NOT EXISTS `member` (
    `member_id`       VARCHAR(36)   NOT NULL,
    `email`           VARCHAR(100)  NOT NULL,
    `password`        VARCHAR(255)  NULL COMMENT '소셜 로그인 시 null',
    `name`            VARCHAR(20)   NOT NULL,
    `phone`           VARCHAR(20)   NULL,
    `profile_img`     VARCHAR(500)  NULL,
    `provider`        VARCHAR(10)   NOT NULL DEFAULT 'LOCAL' COMMENT 'KAKAO / LOCAL',
    `provider_id`     VARCHAR(100)  NULL,
    `email_verified`  CHAR(1)       NOT NULL DEFAULT 'N' COMMENT 'Y/N',
    `role`            VARCHAR(10)   NOT NULL DEFAULT 'USER' COMMENT 'USER / ADMIN',
    `is_active`       TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '탈퇴 시 0',
    `withdrawn_at`    DATETIME      NULL,
    `email_active`    VARCHAR(100)  GENERATED ALWAYS AS (IF(`is_active` = 1, `email`, NULL)) VIRTUAL
                          COMMENT '활성 회원만 유일 — 탈퇴 후 동일 이메일 재가입 허용용',
    `zip_code`        VARCHAR(10)   NULL,
    `address`         VARCHAR(300)  NULL,
    `address_detail`  VARCHAR(100)  NULL COMMENT '상세주소',
    `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`member_id`),
    UNIQUE KEY `uk_member_email_active` (`email_active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `bank_master` (
    `bank_code` VARCHAR(10) NOT NULL,
    `bank_name` VARCHAR(30) NOT NULL,
    PRIMARY KEY (`bank_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 지갑 통합: 회원당 MAIN(실지갑)/DONATION(기부함) 타입별로 최대 1개씩 보유.
-- SOS 전용 지갑은 기능 자체를 접기로 해서 wallet_type에 없음.
-- wallet_type + UNIQUE(member_id, wallet_type)로 "회원당 타입별 1개"를 DB가 보장.
CREATE TABLE IF NOT EXISTS `wallet` (
    `wallet_id`       VARCHAR(36)    NOT NULL,
    `member_id`       VARCHAR(36)    NOT NULL,
    `wallet_type`     VARCHAR(20)    NOT NULL DEFAULT 'MAIN' COMMENT 'MAIN / DONATION',
    `balance`         DECIMAL(15,2)  NOT NULL DEFAULT 0.00,
    `created_at`      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`wallet_id`),
    UNIQUE KEY `uq_wallet_member_type` (`member_id`, `wallet_type`),
    CONSTRAINT `fk_wallet_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`),
    CONSTRAINT `chk_wallet_type` CHECK (`wallet_type` IN ('MAIN', 'DONATION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
    `medical_history` CHAR(1)       NULL DEFAULT 'N' COMMENT 'Y/N',
    `is_active`       TINYINT(1)    NOT NULL DEFAULT 1,
    `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`pet_id`),
    KEY `idx_pet_member` (`member_id`),
    CONSTRAINT `fk_pet_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `pet_document` (
    `doc_id`       VARCHAR(36)   NOT NULL,
    `pet_id`       VARCHAR(36)   NOT NULL,
    `doc_name`     VARCHAR(100)  NULL COMMENT '문서명',
    `doc_type`     VARCHAR(20)   NOT NULL COMMENT 'REG_CERT / VACCINE / MEDICAL',
    `file_url`     VARCHAR(500)  NOT NULL,
    `issued_date`  DATE          NULL,
    `created_at`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`doc_id`),
    KEY `idx_petdoc_pet` (`pet_id`),
    CONSTRAINT `fk_petdoc_pet` FOREIGN KEY (`pet_id`) REFERENCES `pet`(`pet_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `linked_account` (
    `account_id`     VARCHAR(36)   NOT NULL,
    `member_id`      VARCHAR(36)   NOT NULL,
    `bank_code`      VARCHAR(10)   NOT NULL,
    `account_number` VARCHAR(100)  NOT NULL COMMENT '암호화 저장',
    `account_holder` VARCHAR(30)   NOT NULL,
    `connected_id`   VARCHAR(100)  NULL COMMENT 'CODEF Connected ID',
    `is_primary`     TINYINT(1)    NOT NULL DEFAULT 0,
    `status`         VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / INACTIVE',
    `created_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`account_id`),
    KEY `idx_linked_member` (`member_id`),
    CONSTRAINT `fk_linked_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`),
    CONSTRAINT `fk_linked_bank` FOREIGN KEY (`bank_code`) REFERENCES `bank_master`(`bank_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 공동양육 참여자. 그룹 테이블 없이 pet_id를 직접 참조
-- (pet:그룹이 항상 1:1이라 별도 그룹 테이블이 pet_id의 대리키에 불과했음).
-- member_id는 초대 발송 시점엔 NULL, accept 시점에 채워짐.
CREATE TABLE IF NOT EXISTS `pet_group_member` (
    `pet_group_member_id` VARCHAR(36)  NOT NULL,
    `pet_id`               VARCHAR(36)  NOT NULL COMMENT '공동양육 대상 반려동물',
    `member_id`            VARCHAR(36)  NULL COMMENT '초대받은 사용자. 초대 발송 시점엔 NULL, accept 시점에 채워짐',
    `invited_by`           VARCHAR(36)  NOT NULL COMMENT '초대한 사용자',
    `invite_code`          VARCHAR(20)  NULL COMMENT '초대 링크용 코드',
    `role`                 VARCHAR(10)  NOT NULL DEFAULT 'VIEWER' COMMENT 'VIEWER / MANAGER / ADMIN',
    `status`               VARCHAR(10)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / ACCEPTED / REJECTED',
    `accepted_at`          DATETIME     NULL,
    `created_at`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`pet_group_member_id`),
    UNIQUE KEY `uk_pgm_pet_member` (`pet_id`, `member_id`),
    UNIQUE KEY `uk_pgm_invite_code` (`invite_code`),
    KEY `idx_pgm_pet` (`pet_id`),
    CONSTRAINT `fk_pgm_pet` FOREIGN KEY (`pet_id`) REFERENCES `pet`(`pet_id`),
    CONSTRAINT `fk_pgm_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`),
    CONSTRAINT `fk_pgm_inviter` FOREIGN KEY (`invited_by`) REFERENCES `member`(`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `notification_setting` (
    `member_id`                 VARCHAR(36) NOT NULL,
    `payment_enabled`           TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '결제 알림',
    `recurring_payment_enabled` TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '정기 결제 알림',
    `family_share_enabled`      TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '가족 공유 알림',
    `community_enabled`         TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '커뮤니티 알림',
    `marketing_enabled`         TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '마케팅 정보 수신',
    `created_at`                DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`member_id`),
    CONSTRAINT `fk_notif_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `faq` (
    `faq_id`      VARCHAR(36)   NOT NULL,
    `category`    VARCHAR(30)   NULL,
    `title`       VARCHAR(300)  NOT NULL,
    `content`     TEXT          NOT NULL,
    `created_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`faq_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `inquiry` (
    `inquiry_id`   VARCHAR(36)   NOT NULL,
    `member_id`    VARCHAR(36)   NOT NULL,
    `category`     VARCHAR(30)   NULL,
    `title`        VARCHAR(200)  NOT NULL,
    `content`      TEXT          NOT NULL,
    `reply_email`  VARCHAR(100)  NULL COMMENT '답변받을 이메일',
    `status`       VARCHAR(10)   NOT NULL DEFAULT 'WAITING' COMMENT 'WAITING / ANSWERED',
    `answer`       TEXT          NULL,
    `created_at`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `answered_at`  DATETIME      NULL,
    `updated_at`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`inquiry_id`),
    KEY `idx_inquiry_member` (`member_id`),
    CONSTRAINT `fk_inquiry_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `inquiry_attachment` (
    `attachment_id` VARCHAR(36)   NOT NULL,
    `inquiry_id`    VARCHAR(36)   NOT NULL,
    `file_name`     VARCHAR(255)  NOT NULL,
    `file_url`      VARCHAR(500)  NOT NULL,
    `file_size`     BIGINT        NULL COMMENT '바이트 단위',
    `created_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`attachment_id`),
    KEY `idx_ia_inquiry` (`inquiry_id`),
    CONSTRAINT `fk_ia_inquiry` FOREIGN KEY (`inquiry_id`) REFERENCES `inquiry`(`inquiry_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `donation_organization` (
    `organization_id`       VARCHAR(36)    NOT NULL,
    `name`                   VARCHAR(100)   NOT NULL,
    `description`            TEXT           NULL,
    `logo_url`               VARCHAR(500)   NULL,
    `category`               VARCHAR(30)    NULL COMMENT '유기동물 / 의료지원 / 환경 등',
    `total_donation_amount`  DECIMAL(15,2)  NOT NULL DEFAULT 0.00 COMMENT '누적 기부 현황 캐시',
    `created_at`             DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`organization_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- transaction보다 먼저 정의 (transaction.recurring_id가 이 테이블을 참조)
CREATE TABLE IF NOT EXISTS `recurring_payment` (
    `recurring_id`      VARCHAR(36)    NOT NULL,
    `wallet_id`         VARCHAR(36)    NOT NULL,
    `pet_id`            VARCHAR(36)    NULL,
    `product_name`      VARCHAR(100)   NOT NULL,
    `category`          VARCHAR(20)    NULL COMMENT 'HOSPITAL / FOOD / GROOMING / TOY / SOS / ETC',
    `price`             DECIMAL(15,2)  NOT NULL,
    `cycle_days`        INT            NOT NULL COMMENT '주기 (일 단위)',
    `next_payment_date` DATE           NOT NULL,
    `is_active`         TINYINT(1)     NOT NULL DEFAULT 1,
    `created_at`        DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`recurring_id`),
    KEY `idx_recurring_wallet` (`wallet_id`),
    CONSTRAINT `fk_recurring_wallet` FOREIGN KEY (`wallet_id`) REFERENCES `wallet`(`wallet_id`),
    CONSTRAINT `fk_recurring_pet` FOREIGN KEY (`pet_id`) REFERENCES `pet`(`pet_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 모든 자금 흐름(충전/결제/이체/정기결제 실행/기부/공동구매 결제)을 기록하는 단일 원장.
-- member_id 없음: wallet_id -> wallet.member_id로 항상 유도 가능하고(지갑 비공유 확정),
--   pet_group_member로 인한 "행위자 != 소유자" 가능성도 지갑 단위에선 발생하지 않음.
-- counter_wallet_id: 내부 이체(TRANSFER) 시 상대 지갑. recurring_id: 정기결제 실행분 추적 + 배치 멱등성 체크용.
-- chk_txn_type_consistency: txn_type별로 counter_wallet_id/recurring_id 조합이 맞는 경우만 허용.
-- donation_history보다 먼저 정의 (donation_history.txn_id가 이 테이블을 참조)
CREATE TABLE IF NOT EXISTS `transaction` (
    `txn_id`                  VARCHAR(36)    NOT NULL,
    `wallet_id`               VARCHAR(36)    NOT NULL,
    `counter_wallet_id`       VARCHAR(36)    NULL COMMENT '내부 이체 시 상대 지갑 (TRANSFER 전용)',
    `pet_id`                  VARCHAR(36)    NULL COMMENT 'null이면 공통 지출',
    `recurring_id`            VARCHAR(36)    NULL COMMENT '정기결제 실행분인 경우 원본 스케줄 참조',
    `txn_type`                VARCHAR(20)    NOT NULL COMMENT 'DEPOSIT / WITHDRAW / TRANSFER / PAYMENT',
    `price`                   DECIMAL(15,2)  NOT NULL,
    `category`                VARCHAR(20)    NULL COMMENT 'HOSPITAL / FOOD / GROOMING / TOY / SOS / ETC',
    `merchant_name`           VARCHAR(100)   NULL,
    `merchant_category_code`  VARCHAR(20)    NULL,
    `memo`                    VARCHAR(200)   NULL,
    `auto_tagged`             CHAR(1)        NULL DEFAULT 'N' COMMENT 'Y/N',
    `txn_date`                DATETIME       NOT NULL,
    `created_at`               DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`txn_id`),
    KEY `idx_txn_wallet` (`wallet_id`),
    KEY `idx_txn_counter_wallet` (`counter_wallet_id`),
    KEY `idx_txn_date` (`txn_date`),
    KEY `idx_txn_pet` (`pet_id`),
    KEY `idx_txn_recurring` (`recurring_id`),
    CONSTRAINT `fk_txn_wallet` FOREIGN KEY (`wallet_id`) REFERENCES `wallet`(`wallet_id`),
    CONSTRAINT `fk_txn_counter_wallet` FOREIGN KEY (`counter_wallet_id`) REFERENCES `wallet`(`wallet_id`),
    CONSTRAINT `fk_txn_pet` FOREIGN KEY (`pet_id`) REFERENCES `pet`(`pet_id`),
    CONSTRAINT `fk_txn_recurring` FOREIGN KEY (`recurring_id`) REFERENCES `recurring_payment`(`recurring_id`),
    CONSTRAINT `chk_txn_type_consistency` CHECK (
        (`txn_type` = 'TRANSFER' AND `counter_wallet_id` IS NOT NULL AND `recurring_id` IS NULL)
        OR (`txn_type` = 'PAYMENT' AND `counter_wallet_id` IS NULL)
        OR (`txn_type` IN ('DEPOSIT', 'WITHDRAW') AND `counter_wallet_id` IS NULL AND `recurring_id` IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- txn_id: 이 기부가 만든 실제 자금 이동 거래. created_at은 row 생성(DRAFT 진입) 시각,
-- completed_at은 상태가 COMPLETED로 바뀌는 실제 완료 시각(앱에서 채움) — 둘은 다를 수 있음.
-- chk_dh_status_txn으로 status=COMPLETED인데 txn_id/completed_at이 비어있는 상태를 DB 레벨에서 차단.
CREATE TABLE IF NOT EXISTS `donation_history` (
    `donation_id`     VARCHAR(36)    NOT NULL,
    `wallet_id`       VARCHAR(36)    NOT NULL COMMENT 'DONATION 타입 지갑 참조',
    `organization_id` VARCHAR(36)    NOT NULL,
    `txn_id`          VARCHAR(36)    NULL COMMENT '이 기부가 만든 실제 자금 이동 거래',
    `amount`          DECIMAL(15,2)  NOT NULL,
    `status`          VARCHAR(20)    NOT NULL DEFAULT 'COMPLETED' COMMENT 'DRAFT / CONFIRMED / COMPLETED',
    `receipt_url`     VARCHAR(500)   NULL,
    `created_at`      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `completed_at`    DATETIME       NULL COMMENT '상태가 COMPLETED로 바뀐 실제 완료 시각',
    PRIMARY KEY (`donation_id`),
    KEY `idx_dh_wallet` (`wallet_id`),
    KEY `idx_dh_organization` (`organization_id`),
    CONSTRAINT `fk_dh_wallet` FOREIGN KEY (`wallet_id`) REFERENCES `wallet`(`wallet_id`),
    CONSTRAINT `fk_dh_organization` FOREIGN KEY (`organization_id`) REFERENCES `donation_organization`(`organization_id`),
    CONSTRAINT `fk_dh_txn` FOREIGN KEY (`txn_id`) REFERENCES `transaction`(`txn_id`),
    CONSTRAINT `chk_dh_status_txn` CHECK (`status` <> 'COMPLETED' OR (`txn_id` IS NOT NULL AND `completed_at` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `insurance_claim` (
    `claim_id`           VARCHAR(36)    NOT NULL,
    `pet_id`             VARCHAR(36)    NOT NULL,
    `member_id`          VARCHAR(36)    NOT NULL COMMENT '청구서 작성자. 공동양육 시 pet.member_id(소유자)와 다를 수 있어 유지',
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

-- member_id 없음: wallet_id -> wallet.member_id로 유도 가능 (transaction과 동일 논리).
-- target_type='PET' 로그에도 wallet_id가 NOT NULL로 강제되는 점은 추후 재검토 여지 있음.
CREATE TABLE IF NOT EXISTS `activity_log` (
    `log_id`       VARCHAR(36)   NOT NULL,
    `wallet_id`    VARCHAR(36)   NOT NULL,
    `action_type`  VARCHAR(20)   NOT NULL COMMENT 'CREATE / UPDATE / DELETE / PAYMENT / CLAIM',
    `target_type`  VARCHAR(20)   NOT NULL COMMENT 'TRANSACTION / CLAIM / PET',
    `target_id`    VARCHAR(36)   NULL,
    `description`  VARCHAR(300)  NULL,
    `created_at`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`log_id`),
    KEY `idx_log_wallet` (`wallet_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `group_purchase` (
    `gp_id`            VARCHAR(36)    NOT NULL,
    `member_id`        VARCHAR(36)    NOT NULL,
    `product_name`     VARCHAR(200)   NOT NULL,
    `category`         VARCHAR(20)    NULL COMMENT '사료 / 간식 / 용품 / 기타',
    `image`            VARCHAR(500)   NULL COMMENT '상품 이미지 URL',
    `unit_price`       DECIMAL(15,2)  NULL,
    `group_price`      DECIMAL(15,2)  NULL,
    `delivery_method`  VARCHAR(30)    NULL COMMENT '배송 방법',
    `delivery_fee`     DECIMAL(15,2)  NULL DEFAULT 0.00 COMMENT '배송비',
    `delivery_date`    DATE           NULL COMMENT '배송 예정일',
    `description`      TEXT           NULL COMMENT '상품 설명',
    `target_quantity`  INT            NOT NULL DEFAULT 1,
    `current_quantity` INT            NOT NULL DEFAULT 0,
    `status`           VARCHAR(20)    NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / CLOSED / COMPLETED / CANCELLED',
    `deadline`         DATETIME       NULL,
    `created_at`       DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`gp_id`),
    KEY `idx_gp_author` (`member_id`),
    CONSTRAINT `fk_gp_author` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- txn_id: 참여자 결제가 만든 실제 자금 이동 거래 연결 (donation_history와 동일 패턴).
-- chk_gpp_status_txn: PAID인데 txn_id가 비어있는 상태를 DB 레벨에서 차단.
CREATE TABLE IF NOT EXISTS `group_purchase_participant` (
    `participant_id` VARCHAR(36)   NOT NULL,
    `gp_id`          VARCHAR(36)   NOT NULL,
    `member_id`      VARCHAR(36)   NOT NULL,
    `purchase_quantity` INT        NOT NULL DEFAULT 1,
    `recipient_name`   VARCHAR(30)   NULL COMMENT '수령인',
    `recipient_phone`  VARCHAR(20)   NULL COMMENT '수령인 연락처',
    `zip_code`         VARCHAR(10)   NULL,
    `address`          VARCHAR(300)  NULL,
    `address_detail`   VARCHAR(100)  NULL COMMENT '상세주소',
    `payment_status` VARCHAR(10)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / PAID / CANCELLED',
    `paid_amount`    DECIMAL(15,2) NULL,
    `paid_at`        DATETIME      NULL,
    `txn_id`         VARCHAR(36)   NULL COMMENT '이 참여자의 결제가 만든 실제 자금 이동 거래',
    `created_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`participant_id`),
    KEY `idx_gpp_gp` (`gp_id`),
    KEY `idx_gpp_txn` (`txn_id`),
    CONSTRAINT `fk_gpp_gp` FOREIGN KEY (`gp_id`) REFERENCES `group_purchase`(`gp_id`),
    CONSTRAINT `fk_gpp_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`),
    CONSTRAINT `fk_gpp_txn` FOREIGN KEY (`txn_id`) REFERENCES `transaction`(`txn_id`),
    CONSTRAINT `chk_gpp_status_txn` CHECK (`payment_status` <> 'PAID' OR `txn_id` IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `local_support_program` (
    `program_id`   VARCHAR(36)    NOT NULL,
    `region`       VARCHAR(50)    NOT NULL,
    `program_name` VARCHAR(200)   NOT NULL,
    `description`  TEXT           NULL,
    `eligibility`  JSON           NULL COMMENT '자격 조건',
    `apply_url`    VARCHAR(500)   NULL,
    `start_date`   DATE           NULL,
    `end_date`     DATE           NULL,
    `created_at`   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`program_id`),
    KEY `idx_support_region` (`region`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `emergency_hospital` (
    `hospital_id`      VARCHAR(36)     NOT NULL,
    `name`             VARCHAR(100)    NOT NULL,
    `address`          VARCHAR(300)    NOT NULL,
    `phone`            VARCHAR(20)     NULL,
    `latitude`         DECIMAL(10,7)   NOT NULL,
    `longitude`        DECIMAL(10,7)   NOT NULL,
    `location`         POINT GENERATED ALWAYS AS
                            (ST_SRID(POINT(`longitude`, `latitude`), 4326)) STORED NOT NULL SRID 4326,
    `is_24h`           TINYINT(1)      NOT NULL DEFAULT 0,
    `is_holiday_open`  TINYINT(1)      NOT NULL DEFAULT 0,
    `avg_wait_minutes` INT             NULL,
    `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`hospital_id`),
    SPATIAL INDEX `idx_hospital_location_geo` (`location`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
