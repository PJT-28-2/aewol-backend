-- =====================================================================
-- V4: 계좌 중복 연동 방지 / 1원 인증 / 고객센터 컬럼명 정리 / 문의 채번 /
--     회원 소셜 중복 방지 / 반려동물 등록증 구조화 / 응급병원 upsert 보완
-- =====================================================================

-- ---------------------------------------------------------------------
-- 0. linked_account 계좌 중복 등록 방지
--    account_number는 암호화 저장이라 그 자체로는 UNIQUE를 걸 수 없어,
--    별도 해시 컬럼을 두고 회원당 활성(ACTIVE) 계좌 해시만 유니크하게 묶는다.
--    account_number_hash_active: status가 ACTIVE일 때만 해시를 노출하는
--    가상 컬럼 — INACTIVE로 바뀐 계좌는 유니크 제약에서 제외되어 같은
--    계좌를 다시 등록할 수 있다.
-- ---------------------------------------------------------------------
ALTER TABLE `linked_account`
    ADD COLUMN `account_number_hash` CHAR(64) NOT NULL AFTER `account_number`,
    ADD COLUMN `account_number_hash_active` CHAR(64)
        GENERATED ALWAYS AS (IF(`status` = 'ACTIVE', `account_number_hash`, NULL)) VIRTUAL
        AFTER `account_number_hash`;

ALTER TABLE `linked_account`
    ADD UNIQUE KEY `uk_linked_member_account_hash` (`member_id`, `account_number_hash_active`);

-- ---------------------------------------------------------------------
-- 0-1. pet.medical_history 타입 수정 (CHAR(1) Y/N 플래그 -> 자유 서술 텍스트)
--    PetRegisterView.vue / PetEditView.vue가 "병력 (선택)"을 자유 텍스트로
--    입력받아 medicalHistory로 그대로 전송하고, PetServiceImpl도 이미
--    pet.get("medical_history")를 (String)으로 캐스팅하고 있어 코드가
--    기대하는 값과 CHAR(1) Y/N 스키마가 어긋나 있었다. Y/N DEFAULT는
--    텍스트 컬럼에 의미가 없어 함께 제거한다.
-- ---------------------------------------------------------------------
ALTER TABLE `pet`
    MODIFY COLUMN `medical_history` VARCHAR(500) NULL;

-- ---------------------------------------------------------------------
-- 0-2. recurring_payment.cycle_days -> payment_day
--    RecurringRegisterView.vue의 dayOptions(1~28)는 "매월 며칠에" 결제할지
--    고르는 UI이고 stores/payment.js가 cycleDay로 전송하는데, 기존
--    cycle_days는 "며칠 간격으로"(예: 7/14/30일)라는 다른 개념이라 그대로
--    쓰면 정기결제 스케줄 계산이 어긋난다. "매월 N일" 의미로 컬럼명/제약을
--    맞춘다. 말일이 없는 달(2월 등) 처리를 피하기 위해 프론트가 1~28로
--    제한하는 것과 동일하게 CHECK로 DB 레벨에서도 강제한다.
-- ---------------------------------------------------------------------
ALTER TABLE `recurring_payment`
    CHANGE COLUMN `cycle_days` `payment_day` INT NOT NULL COMMENT '매월 결제일 (1~28)',
    ADD CONSTRAINT `chk_recurring_payment_day` CHECK (`payment_day` BETWEEN 1 AND 28);

-- ---------------------------------------------------------------------
-- 1. account_verification (1원 인증 진행 상태)
--    transaction_id: 서로게이트 키가 아닌 앱이 발급하는 자연키
--    (예: TX20260722001) 형식을 그대로 유지한다. bank_master.bank_code와
--    동일한 이유로 BIGINT 전환 대상에서 제외.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `account_verification` (
    `transaction_id`     VARCHAR(50)   NOT NULL COMMENT '앱에서 생성하는 형식 그대로 유지 (예: TX20260722001)',
    `member_id`          BIGINT        NOT NULL,
    `bank_code`          VARCHAR(10)   NOT NULL,
    `account_number`     VARCHAR(100)  NOT NULL COMMENT '암호화 저장',
    `account_holder`     VARCHAR(30)   NOT NULL,
    `verification_code`  VARCHAR(10)   NOT NULL COMMENT 'CODEF inPrintType=1 랜덤 한글 4자',
    `status`             VARCHAR(10)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / VERIFIED / USED',
    `requested_at`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `verified_at`        DATETIME      NULL,
    PRIMARY KEY (`transaction_id`),
    KEY `idx_av_member` (`member_id`),
    CONSTRAINT `fk_av_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`),
    CONSTRAINT `fk_av_bank` FOREIGN KEY (`bank_code`) REFERENCES `bank_master`(`bank_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- 1-1. faq 컬럼명 정리 (title -> question, content -> answer)
-- ---------------------------------------------------------------------
ALTER TABLE `faq`
    CHANGE COLUMN `title` `question` VARCHAR(300) NOT NULL,
    CHANGE COLUMN `content` `answer` TEXT NOT NULL;

-- ---------------------------------------------------------------------
-- 2. inquiry.inquiry_number (문의 접수번호, 사용자 노출용 채번)
-- ---------------------------------------------------------------------
ALTER TABLE `inquiry`
    ADD COLUMN `inquiry_number` VARCHAR(20) NULL COMMENT '사용자 노출용 접수번호' AFTER `inquiry_id`,
    ADD UNIQUE KEY `uk_inquiry_number` (`inquiry_number`);

-- ---------------------------------------------------------------------
-- 3. member(provider, provider_id) 복합 UNIQUE (소셜 계정 중복 연동 방지)
--    provider_id가 NULL인 LOCAL 회원은 UNIQUE 제약에서 서로 충돌하지 않는다.
-- ---------------------------------------------------------------------
ALTER TABLE `member`
    ADD UNIQUE KEY `uk_member_provider` (`provider`, `provider_id`);

-- ---------------------------------------------------------------------
-- 4. pet_document 정리 + pet_registration 분리
--    REGISTRATION 타입은 파일 업로드가 아니라 구조화 데이터로 저장하므로
--    file_url이 NULL일 수 있다.
-- ---------------------------------------------------------------------
ALTER TABLE `pet_document`
    MODIFY COLUMN `doc_type` VARCHAR(30) NOT NULL COMMENT 'REGISTRATION / VACCINATION / MEDICAL_CONFIRMATION',
    MODIFY COLUMN `file_url` VARCHAR(255) NULL COMMENT '사진, REGISTRATION은 NULL';

CREATE TABLE IF NOT EXISTS `pet_registration` (
    `doc_id`          BIGINT       NOT NULL COMMENT 'pet_document.doc_id 1:1 참조',
    `pet_id`          BIGINT       NOT NULL,
    `reg_number`      VARCHAR(30)  NULL COMMENT 'pet.reg_number와 동일명 <- dogRegNo',
    `name`            VARCHAR(50)  NULL COMMENT 'pet.name과 동일명 <- dogNm',
    `breed`           VARCHAR(50)  NULL COMMENT 'pet.breed와 동일명 <- kindNm',
    `gender`          VARCHAR(10)  NULL COMMENT 'pet.gender와 동일명 <- sexNm',
    `neutered`        CHAR(1)      NULL COMMENT 'pet.neutered와 동일명 <- neuterYn',
    `birth_date`      VARCHAR(20)  NULL COMMENT 'pet.birth_date와 동일명, 포맷 미확정이라 String 유지 <- birthDt',
    `rfid_cd`         VARCHAR(30)  NULL COMMENT 'pet에 없음 <- rfidCd',
    `rfid_gubun`      CHAR(1)      NULL COMMENT '등록칩 구분 Y:내장 M:외장 N:인식표 <- rfidGubun',
    `org_nm`          VARCHAR(50)  NULL COMMENT '관할기관 <- orgNm',
    `office_tel`      VARCHAR(20)  NULL COMMENT '관할기관 연락처 <- officeTel',
    `apr_gbn_nm`      VARCHAR(10)  NULL COMMENT '승인여부 <- aprGbnNm',
    `reg_tm`          DATETIME     NULL COMMENT '등록일시 <- regTm',
    `apr_tm`          DATETIME     NULL COMMENT '승인일시 <- aprTm',
    `last_synced_at`  DATETIME     NULL COMMENT '자체 재동기화 시각',
    PRIMARY KEY (`doc_id`),
    KEY `idx_petreg_pet` (`pet_id`),
    CONSTRAINT `fk_petreg_doc` FOREIGN KEY (`doc_id`) REFERENCES `pet_document`(`doc_id`),
    CONSTRAINT `fk_petreg_pet` FOREIGN KEY (`pet_id`) REFERENCES `pet`(`pet_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- 5. emergency_hospital 보완
--    - external_mng_no: 외부 소스(APMS 등) upsert 키
--    - is_holiday_open: 정보 미확인 상태를 표현하기 위해 nullable로 완화
--    - latitude/longitude/location: 좌표 확보 실패 병원도 다른 정보는
--      저장하되 지도 노출만 제외하는 정책으로 nullable 전환.
--      EmergencyMapper.findNearby는 HAVING distance_km <= radiusKm이라
--      좌표가 NULL이면 distance_km도 NULL이 되어 결과에서 자동 제외된다.
--      MySQL은 SPATIAL INDEX 컬럼이 NOT NULL이어야 하므로 location을
--      nullable로 바꾸면 기존 idx_hospital_location_geo는 유지할 수 없다.
--      findNearby는 이 인덱스를 쓰지 않고 위/경도로 직접 Haversine 계산을
--      하고 있어 인덱스 제거로 인한 조회 영향은 없다.
-- ---------------------------------------------------------------------
ALTER TABLE `emergency_hospital`
    ADD COLUMN `external_mng_no` VARCHAR(30) NULL COMMENT '외부 소스 관리번호 (upsert 키)' AFTER `hospital_id`,
    ADD UNIQUE KEY `uk_hospital_external_mng_no` (`external_mng_no`);

ALTER TABLE `emergency_hospital`
    MODIFY COLUMN `is_holiday_open` TINYINT(1) NULL COMMENT '정보 미확인 시 NULL';

ALTER TABLE `emergency_hospital`
    DROP INDEX `idx_hospital_location_geo`,
    DROP COLUMN `location`;

ALTER TABLE `emergency_hospital`
    MODIFY COLUMN `latitude`  DECIMAL(10,7) NULL,
    MODIFY COLUMN `longitude` DECIMAL(10,7) NULL;

ALTER TABLE `emergency_hospital`
    ADD COLUMN `location` POINT GENERATED ALWAYS AS
        (ST_SRID(POINT(`longitude`, `latitude`), 4326)) STORED NULL SRID 4326;
