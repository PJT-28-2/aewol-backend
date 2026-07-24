-- ================================================================
-- V2 — 누락 FK/인덱스 보완 + API 명세 정합성 맞춤 스키마 보완
-- ================================================================
-- 전제 1: 프로젝트 DB는 MySQL로 확정 → 위치 검색은 PostGIS 대신
--         MySQL 8.0 네이티브 공간 기능(POINT + SPATIAL INDEX) 사용.
-- 전제 2: wallet은 member와 1:1 (지갑 자체는 공유 안 됨, 기존 구조 유지).
--         family_group은 pet과 1:1 — "지갑 공유"가 아니라 "이 반려동물에
--         대해 각 멤버가 자기 지갑에서 쓴 사용 내역을 서로 볼 수 있게
--         하는" 목적. 공동양육자도 각자 자기 wallet에서 결제하고,
--         transaction.pet_id로 같은 pet에 묶인 지출을 그룹원끼리 조회하는 구조.
-- 전제 3: 목적별 예산 관리 기능 제외 → bucket 계층 제거. transaction은
--         pet_id를 직접 참조하고, 목적 분류는 category 컬럼으로 대체.
--         SOS 포켓은 매번 SUM() 집계 대신 sos_wallet 캐시 잔액 테이블로 관리.
-- ================================================================


-- ================================================================
-- PART A. 누락 FK/인덱스 보완
-- ================================================================

-- ----------------------------------------------------------------
-- A1. bucket 제거 → transaction/recurring_payment가 pet_id 직접 참조
--     SOS 포켓 전용 캐시 잔액 테이블(sos_wallet) 신설
-- ----------------------------------------------------------------
ALTER TABLE `transaction`
DROP COLUMN `bucket_id`,
    ADD COLUMN `pet_id` VARCHAR(36) NULL COMMENT 'null이면 공통 지출' AFTER `wallet_id`,
    ADD KEY `idx_txn_pet` (`pet_id`),
    ADD CONSTRAINT `fk_txn_pet` FOREIGN KEY (`pet_id`) REFERENCES `pet`(`pet_id`);

ALTER TABLE `recurring_payment`
DROP COLUMN `bucket_id`,
    ADD COLUMN `pet_id` VARCHAR(36) NULL AFTER `wallet_id`,
    ADD CONSTRAINT `fk_recurring_pet` FOREIGN KEY (`pet_id`) REFERENCES `pet`(`pet_id`);

DROP TABLE IF EXISTS `bucket`;

CREATE TABLE IF NOT EXISTS `sos_wallet` (
                                            `sos_wallet_id` VARCHAR(36)   NOT NULL,
    `member_id`     VARCHAR(36)   NOT NULL,
    `balance`       DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    `updated_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`sos_wallet_id`),
    UNIQUE KEY `uk_sos_wallet_member` (`member_id`),
    CONSTRAINT `fk_sos_wallet_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- balance는 캐시값. SOS 충전/결제 시 transaction에 category='SOS' 로우를 추가하는
-- 동시에 이 balance를 애플리케이션 트랜잭션으로 같이 갱신할 것 (donation_pot과 동일 패턴).

ALTER TABLE `activity_log`
    MODIFY COLUMN `target_type` VARCHAR(20) NOT NULL COMMENT 'TRANSACTION / CLAIM / PET';
-- bucket 제거로 target_type의 'BUCKET' 값은 더 이상 쓰이지 않음


-- ----------------------------------------------------------------
-- A2. emergency_hospital 위치 검색용 SPATIAL INDEX 적용
-- ----------------------------------------------------------------

-- 기존 latitude/longitude 컬럼은 표시/응답용으로 그대로 유지하고,
-- 그 값으로부터 계산되는 생성 컬럼(POINT, SRID 4326) 추가
ALTER TABLE `emergency_hospital`
    ADD COLUMN `location` POINT
        GENERATED ALWAYS AS (ST_SRID(POINT(`longitude`, `latitude`), 4326)) STORED
        NOT NULL AFTER `longitude`;

-- 공간 인덱스 생성 (기존 (latitude, longitude) 복합 인덱스는 대체되므로 제거)
ALTER TABLE `emergency_hospital`
DROP KEY `idx_hospital_location`,
    ADD SPATIAL INDEX `idx_hospital_location_geo` (`location`);

-- 참고: 위치 기반 조회 쿼리 예시 — "반경 5km 이내 응급병원, 가까운 순"
--
-- SELECT hospital_id, name, address, phone, is_24h,
--        ST_Distance_Sphere(location, ST_SRID(POINT(:lng, :lat), 4326)) AS distance_m
-- FROM emergency_hospital
-- WHERE ST_Distance_Sphere(location, ST_SRID(POINT(:lng, :lat), 4326)) <= 5000
-- ORDER BY distance_m
-- LIMIT 10;
--
-- 주의: WHERE절에 ST_Distance_Sphere를 직접 쓰면 SPATIAL INDEX를 못 타고
-- 풀스캔함. 인덱스를 실제로 활용하려면 MBRContains로 대략적인 사각 범위
-- (bounding box)를 먼저 좁힌 뒤 정확한 거리로 필터링:
--
-- SET @lat = :lat, @lng = :lng, @radius_deg = 0.05; -- 대략 5km
-- SELECT hospital_id, name,
--        ST_Distance_Sphere(location, ST_SRID(POINT(@lng, @lat), 4326)) AS distance_m
-- FROM emergency_hospital
-- WHERE MBRContains(
--         ST_SRID(ST_GeomFromText(CONCAT(
--             'POLYGON((',
--             @lng-@radius_deg,' ',@lat-@radius_deg,',',
--             @lng+@radius_deg,' ',@lat-@radius_deg,',',
--             @lng+@radius_deg,' ',@lat+@radius_deg,',',
--             @lng-@radius_deg,' ',@lat+@radius_deg,',',
--             @lng-@radius_deg,' ',@lat-@radius_deg,'))'
--         )), 4326),
--         location
--       )
-- HAVING distance_m <= 5000
-- ORDER BY distance_m
-- LIMIT 10;


-- ================================================================
-- PART B. API 명세 정합성 맞춤 스키마 보완
-- ================================================================

-- ----------------------------------------------------------------
-- B1. FAMILY_GROUP 구조 신설
--     API의 familyGroupId 개념에 맞춰 pet 기준 공유 그룹 엔티티 추가.
--     기존 shared_access(wallet 직접 공유)를 family_group_member로 재편.
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `family_group` (
                                              `group_id`    VARCHAR(36)  NOT NULL,
    `pet_id`      VARCHAR(36)  NOT NULL COMMENT '사용내역을 공유할 반려동물 (pet과 1:1)',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`group_id`),
    UNIQUE KEY `uk_group_pet` (`pet_id`),
    CONSTRAINT `fk_group_pet` FOREIGN KEY (`pet_id`) REFERENCES `pet`(`pet_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `shared_access`;

CREATE TABLE IF NOT EXISTS `family_group_member` (
                                                     `family_group_member_id` VARCHAR(36)  NOT NULL,
    `group_id`                VARCHAR(36)  NOT NULL,
    `member_id`                VARCHAR(36)  NULL COMMENT '초대받은 사용자. 초대 발송 시점엔 NULL, accept 시점에 채워짐',
    `invited_by`               VARCHAR(36)  NOT NULL COMMENT '초대한 사용자',
    `invite_code`              VARCHAR(20)  NULL COMMENT '초대 링크용 코드 (/api/invite/{inviteCode})',
    `role`                     VARCHAR(10)  NOT NULL DEFAULT 'VIEWER' COMMENT 'VIEWER / MANAGER / ADMIN',
    `status`                   VARCHAR(10)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / ACCEPTED / REJECTED',
    `accepted_at`              DATETIME     NULL,
    `created_at`               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`family_group_member_id`),
    UNIQUE KEY `uk_fgm_group_member` (`group_id`, `member_id`),
    UNIQUE KEY `uk_fgm_invite_code` (`invite_code`),
    KEY `idx_fgm_group` (`group_id`),
    CONSTRAINT `fk_fgm_group` FOREIGN KEY (`group_id`) REFERENCES `family_group`(`group_id`),
    CONSTRAINT `fk_fgm_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`),
    CONSTRAINT `fk_fgm_inviter` FOREIGN KEY (`invited_by`) REFERENCES `member`(`member_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 참고: 그룹 단위 조회는 이제 bucket 없이 transaction.pet_id로 바로 처리.
-- GET /api/family-groups/{familyGroupId}/activity-logs
--   → family_group.pet_id 로 transaction.pet_id 매칭 → 해당 거래들의 wallet_id로
--     activity_log 조회 (여러 멤버의 wallet을 가로질러 모음)
-- GET /api/family-groups/{familyGroupId}/contribution-summary
--   → family_group.pet_id 로 transaction.pet_id 매칭 → member_id별
--     SUM(amount)로 기여 비율 집계
-- bucket 계층이 없어지면서 조인이 한 단계 줄어듦 (transaction.pet_id 직접 필터).


-- ----------------------------------------------------------------
-- B2. 알림 설정 (GET/PATCH /api/users/me/settings/notifications)
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `notification_setting` (
                                                      `member_id`         VARCHAR(36) NOT NULL,
    `push_enabled`      TINYINT(1)  NOT NULL DEFAULT 1,
    `email_enabled`     TINYINT(1)  NOT NULL DEFAULT 1,
    `marketing_enabled` TINYINT(1)  NOT NULL DEFAULT 0,
    `updated_at`        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`member_id`),
    CONSTRAINT `fk_notif_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ----------------------------------------------------------------
-- B3. FAQ (GET /api/support/faqs, /api/support/faqs/{faqId})
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `faq` (
                                     `faq_id`      VARCHAR(36)   NOT NULL,
    `category`    VARCHAR(30)   NULL,
    `title`       VARCHAR(300)  NOT NULL,
    `content`     TEXT          NOT NULL,
    `created_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`faq_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ----------------------------------------------------------------
-- B4. 1:1 문의 (POST/GET /api/support/inquiries, /{inquiryId})
-- ----------------------------------------------------------------
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


-- ----------------------------------------------------------------
-- B4-1. INQUIRY_ATTACHMENT (문의 첨부파일 — inquiry 1:N)
-- ----------------------------------------------------------------
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


-- ----------------------------------------------------------------
-- B5. 기부처 마스터 신설 + donation_history 연결
--     (GET /api/donation-organizations, /{organizationId}, /donation-summary)
-- ----------------------------------------------------------------
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

ALTER TABLE `donation_history`
    ADD COLUMN `organization_id` VARCHAR(36)  NOT NULL AFTER `pot_id`,
    ADD COLUMN `status`          VARCHAR(20)  NOT NULL DEFAULT 'COMPLETED'
        COMMENT 'DRAFT / CONFIRMED / COMPLETED' AFTER `amount`,
    ADD COLUMN `receipt_url`     VARCHAR(500) NULL AFTER `status`,
DROP COLUMN `recipient_name`,
    ADD KEY `idx_dh_organization` (`organization_id`),
    ADD CONSTRAINT `fk_dh_organization` FOREIGN KEY (`organization_id`)
        REFERENCES `donation_organization`(`organization_id`);


-- ----------------------------------------------------------------
-- B6. 잔돈 자동 적립 내역 (GET /api/piggy-banks/{piggyBankId}/round-up-transactions)
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `round_up_transaction` (
                                                      `round_up_id`    VARCHAR(36)    NOT NULL,
    `pot_id`         VARCHAR(36)    NOT NULL,
    `txn_id`         VARCHAR(36)    NOT NULL COMMENT '적립 계기가 된 원본 결제 거래',
    `rounded_amount` DECIMAL(15,2)  NOT NULL,
    `created_at`     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`round_up_id`),
    KEY `idx_rut_pot` (`pot_id`),
    CONSTRAINT `fk_rut_pot` FOREIGN KEY (`pot_id`) REFERENCES `donation_pot`(`pot_id`),
    CONSTRAINT `fk_rut_txn` FOREIGN KEY (`txn_id`) REFERENCES `transaction`(`txn_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ----------------------------------------------------------------
-- B7. 저금통 출금 이력 (POST /api/piggy-banks/{piggyBankId}/withdrawals)
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `pot_withdrawal` (
                                                `withdrawal_id` VARCHAR(36)   NOT NULL,
    `pot_id`         VARCHAR(36)   NOT NULL,
    `amount`         DECIMAL(15,2) NOT NULL,
    `withdrawn_at`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`withdrawal_id`),
    KEY `idx_pw_pot` (`pot_id`),
    CONSTRAINT `fk_pw_pot` FOREIGN KEY (`pot_id`) REFERENCES `donation_pot`(`pot_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ----------------------------------------------------------------
-- B8. 공동구매 결제 상태/금액 보완
--     (payment-preview, payments, status, payments 취소)
-- ----------------------------------------------------------------
ALTER TABLE `gp_participant`
    ADD COLUMN `payment_status` VARCHAR(10)   NOT NULL DEFAULT 'PENDING'
    COMMENT 'PENDING / PAID / CANCELLED' AFTER `quantity`,
    ADD COLUMN `paid_amount`    DECIMAL(15,2) NULL AFTER `payment_status`,
    ADD COLUMN `paid_at`        DATETIME      NULL AFTER `paid_amount`;


-- ----------------------------------------------------------------
-- B9. 회원 탈퇴 처리 (DELETE /api/users/me)
-- ----------------------------------------------------------------
ALTER TABLE `member`
    ADD COLUMN `is_active`    TINYINT(1) NOT NULL DEFAULT 1 AFTER `role`,
    ADD COLUMN `withdrawn_at` DATETIME   NULL AFTER `is_active`;

-- B9-1. 탈퇴 후 같은 이메일로 재가입 가능하도록 unique 제약 범위를 "활성 회원"으로 한정.
-- email 컬럼 자체의 uk_member_email(전체 유일)을 없애고, is_active=1인 행에서만
-- unique가 걸리는 가상 컬럼(email_active)을 만들어 그쪽에 unique 인덱스를 건다.
-- MySQL unique index는 NULL을 여러 개 허용하므로, 탈퇴 회원(is_active=0)은
-- email_active가 NULL이 되어 같은 이메일로 몇 번을 탈퇴해도 충돌하지 않는다.
-- 로그인/중복확인 쿼리는 email 대신 email_active 로 조회하도록 애플리케이션 수정 필요.
ALTER TABLE `member`
DROP INDEX `uk_member_email`,
    ADD COLUMN `email_active` VARCHAR(100)
        GENERATED ALWAYS AS (IF(`is_active` = 1, `email`, NULL)) VIRTUAL AFTER `email`,
    ADD UNIQUE KEY `uk_member_email_active` (`email_active`);


-- ----------------------------------------------------------------
-- B10. 은행 마스터 (GET /api/banks) — 선택. 정적 데이터를 코드로 관리해도
--      무방하나 API가 명시돼 있어 최소 형태로 추가
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `bank_master` (
                                             `bank_code` VARCHAR(10) NOT NULL,
    `bank_name` VARCHAR(30) NOT NULL,
    PRIMARY KEY (`bank_code`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ================================================================
-- PART C. 정규화 위반 정리 (이행적 종속 제거)
-- ================================================================

-- ----------------------------------------------------------------
-- C1. pet.icon_type — species에 종속(species가 DOG/CAT이면 icon_type도 그대로
--     결정됨). 프론트에서 species로 아이콘 매핑하면 되므로 컬럼 제거.
-- ----------------------------------------------------------------
ALTER TABLE `pet` DROP COLUMN `icon_type`;

-- ----------------------------------------------------------------
-- C2. (제거됨) bucket.is_sos 관련 정리는 A1에서 bucket 테이블 자체를
--     DROP했으므로 더 이상 해당 없음. SOS 여부는 transaction.category='SOS'
--     + sos_wallet으로 대체됨.
-- ----------------------------------------------------------------

-- ----------------------------------------------------------------
-- C3. linked_account.bank_name — bank_code에 종속. bank_master가 이미
--     있으므로 bank_name 중복 저장을 없애고 FK로 연결.
-- ----------------------------------------------------------------
ALTER TABLE `linked_account`
DROP COLUMN `bank_name`,
    ADD CONSTRAINT `fk_linked_bank` FOREIGN KEY (`bank_code`) REFERENCES `bank_master`(`bank_code`);
-- 주의: 기존에 linked_account에 저장된 bank_code 값이 bank_master에
-- 먼저 시드(seed)되어 있어야 이 FK 추가가 성공함. bank_master 초기 데이터
-- (은행코드/이름 목록)를 이 마이그레이션 전에 넣어둘 것.


-- ================================================================
-- PART D. 기획 변경 반영 (컬럼 제거 / 개명 / 추가)
-- ================================================================

-- ----------------------------------------------------------------
-- D1. member — nickname, income_level 제거
-- ----------------------------------------------------------------
ALTER TABLE `member`
DROP COLUMN `nickname`,
    DROP COLUMN `income_level`;

-- ----------------------------------------------------------------
-- D2. recurring_payment.amount → price
-- ----------------------------------------------------------------
ALTER TABLE `recurring_payment`
    CHANGE COLUMN `amount` `price` DECIMAL(15,2) NOT NULL;

-- ----------------------------------------------------------------
-- D3. transaction.amount → price
-- ----------------------------------------------------------------
ALTER TABLE `transaction`
    CHANGE COLUMN `amount` `price` DECIMAL(15,2) NOT NULL;

-- ----------------------------------------------------------------
-- D4. pet.profile_img 제거
-- ----------------------------------------------------------------
ALTER TABLE `pet` DROP COLUMN `profile_img`;

-- ----------------------------------------------------------------
-- D5. group_purchase — product_url → image로 개명, 배송 관련 컬럼 4개 추가
-- ----------------------------------------------------------------
ALTER TABLE `group_purchase`
    CHANGE COLUMN `product_url` `image` VARCHAR(500) NULL COMMENT '상품 이미지 URL',
    ADD COLUMN `delivery_method` VARCHAR(30)   NULL COMMENT '배송 방법' AFTER `group_price`,
    ADD COLUMN `delivery_fee`    DECIMAL(15,2) NULL DEFAULT 0.00 COMMENT '배송비' AFTER `delivery_method`,
    ADD COLUMN `delivery_date`   DATE          NULL COMMENT '배송 예정일' AFTER `delivery_fee`,
    ADD COLUMN `description`     TEXT          NULL COMMENT '상품 설명' AFTER `delivery_date`;

-- ----------------------------------------------------------------
-- D6. pet_document — doc_name 추가
-- ----------------------------------------------------------------
ALTER TABLE `pet_document`
    ADD COLUMN `doc_name` VARCHAR(100) NULL COMMENT '문서명' AFTER `pet_id`;

-- ----------------------------------------------------------------
-- D7. recurring_payment — category 추가 (transaction과 동일한 값 집합)
-- ----------------------------------------------------------------
ALTER TABLE `recurring_payment`
    ADD COLUMN `category` VARCHAR(20) NULL COMMENT 'HOSPITAL / FOOD / GROOMING / TOY / SOS / ETC' AFTER `product_name`;

-- ----------------------------------------------------------------
-- D8. group_purchase.author_id → member_id 컬럼명 변경
-- ----------------------------------------------------------------
ALTER TABLE `group_purchase`
    RENAME COLUMN `author_id` TO `member_id`;