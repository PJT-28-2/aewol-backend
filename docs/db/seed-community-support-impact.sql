-- =====================================================================
-- DEV_TEST_ONLY: V1~V5 적용 후 개발/시연 DB에서만 실행한다.
--
-- 신 계보 스키마(BIGINT AUTO_INCREMENT, wallet_type 통합 지갑) 기준.
-- AUTO_INCREMENT 컬럼에 시드 전용 명시 ID(9000번대)를 사용해
-- 런타임 생성 데이터와 절대 겹치지 않게 한다.
-- 계정 비밀번호는 모두 test1234 (BCrypt).
--   owner@example.test  : 보리 소유자, 공동육아/지원정책/기부 데이터 보유
--   family@example.test : 공동육아 참여자
-- =====================================================================
START TRANSACTION;

INSERT INTO `member` (`member_id`, `email`, `password`, `name`, `phone`, `zip_code`, `address`, `address_detail`, `email_verified`)
VALUES
 (9001, 'owner@example.test',  '$2a$10$PU62RE.CxfBkpODKBvCfDehy7q5x6KW6TwKnCaRfnGiVLpfPsDlZ2', '김애월', '01011111111', '04524', '서울특별시 중구 세종대로 110', '애월아파트 101동 1001호', 'Y'),
 (9002, 'family@example.test', '$2a$10$PU62RE.CxfBkpODKBvCfDehy7q5x6KW6TwKnCaRfnGiVLpfPsDlZ2', '이지원', '01022222222', '04524', '서울특별시 중구 세종대로 110', '애월아파트 101동 1002호', 'Y')
ON DUPLICATE KEY UPDATE `password`=VALUES(`password`), `name`=VALUES(`name`), `address`=VALUES(`address`);

-- 회원당 MAIN(실지갑) 1개 + owner는 DONATION(짜투리 저금통) 1개
INSERT INTO `wallet` (`wallet_id`, `member_id`, `wallet_type`, `balance`)
VALUES
 (9001, 9001, 'MAIN',     500000),
 (9002, 9002, 'MAIN',     300000),
 (9003, 9001, 'DONATION',  12400)
ON DUPLICATE KEY UPDATE `balance`=VALUES(`balance`);

INSERT INTO `pet` (`pet_id`,`member_id`,`name`,`species`,`breed`,`birth_date`,`gender`,`neutered`,`reg_number`)
VALUES (9001, 9001, '보리', 'DOG', '말티즈', '2024-03-15', 'FEMALE', 'Y', 'TEST-REG-001')
ON DUPLICATE KEY UPDATE `name`=VALUES(`name`), `neutered`=VALUES(`neutered`), `reg_number`=VALUES(`reg_number`);

INSERT INTO `shared_access`
 (`access_id`,`wallet_id`,`pet_id`,`member_id`,`invited_by`,`invite_code`,`recipient_type`,`recipient_value`,`role`,`status`,`accepted_at`,`expires_at`)
VALUES
 (9001, 9001, 9001, 9002, 9001, 'seed-family-code',  'EMAIL', 'family@example.test',  'MANAGER', 'ACCEPTED', NOW(), NULL),
 (9002, 9001, 9001, NULL, 9001, 'seed-pending-code', 'EMAIL', 'pending@example.test', 'VIEWER',  'PENDING',  NULL, DATE_ADD(NOW(), INTERVAL 7 DAY))
ON DUPLICATE KEY UPDATE `role`=VALUES(`role`), `status`=VALUES(`status`), `expires_at`=VALUES(`expires_at`);

-- transaction에는 member_id가 없다 — 행위자는 wallet_id -> wallet.member_id로 유도된다
INSERT INTO `transaction`
 (`txn_id`,`wallet_id`,`pet_id`,`txn_type`,`price`,`category`,`merchant_name`,`memo`,`txn_date`)
VALUES
 (9001, 9001, 9001, 'PAYMENT', 72600, 'HOSPITAL', '애월동물병원', '정기 검진', NOW()-INTERVAL 3 DAY),
 (9002, 9002, 9001, 'PAYMENT', 48000, 'FOOD',     '보리네 펫샵', '사료 구매', NOW()-INTERVAL 2 DAY),
 (9003, 9003, NULL, 'WITHDRAW', 3000, 'ETC',      '테스트 동물보호소', '테스트 기부', NOW()-INTERVAL 1 DAY)
ON DUPLICATE KEY UPDATE `price`=VALUES(`price`), `txn_date`=VALUES(`txn_date`);

INSERT INTO `activity_log`
 (`log_id`,`wallet_id`,`pet_id`,`action_type`,`target_type`,`target_id`,`title`,`description`,`metadata`)
VALUES
 (9001, 9001, 9001, 'PAYMENT', 'TRANSACTION', 9001, '이번 달 병원비를 공동 정산했어요', '정기 검진 · 72,600원', JSON_OBJECT('displayTime','오늘')),
 (9002, 9001, 9001, 'CREATE',  'PET',         9001, '이지원님이 공동육아에 참여했어요', 'MANAGER 권한',        JSON_OBJECT('displayTime','어제'))
ON DUPLICATE KEY UPDATE `title`=VALUES(`title`), `description`=VALUES(`description`), `metadata`=VALUES(`metadata`);

INSERT INTO `gov24_public_service` (`service_id`,`service_name`,`support_type`,`service_purpose_summary`,`organization_name`,`service_category`,`synced_at`)
VALUES
 ('seed-gov-pet-001', '서울시 반려동물 중성화 지원',  '현금', '중성화 비용 일부 지원', '서울특별시 동물복지과',       '동물복지', NOW()),
 ('seed-gov-pet-002', '제주 유기동물 입양비 지원',    '현금', '입양 초기 비용 지원',   '제주특별자치도 동물복지과', '동물복지', NOW())
ON DUPLICATE KEY UPDATE `service_name`=VALUES(`service_name`), `synced_at`=VALUES(`synced_at`);

INSERT INTO `local_support_program`
 (`program_id`,`source_service_id`,`source_type`,`region`,`program_name`,`description`,`summary`,`agency_name`,`benefit_summary`,`eligibility`,`target_species`,`apply_url`,`period_text`,`application_method`,`start_date`,`end_date`,`is_active`,`synced_at`)
VALUES
 (9001, 'seed-gov-pet-001', 'GOV24', '서울특별시',     '서울시 반려동물 중성화 지원', '중성화 수술비를 지원합니다.', '조건 충족 시 최대 15만원',  '서울특별시 동물복지과',       '최대 150,000원', JSON_OBJECT('test',true), 'DOG', 'https://example.test/support/1', '2026년 12월 31일까지', '온라인 신청',      CURDATE()-INTERVAL 30 DAY, CURDATE()+INTERVAL 180 DAY, 1, NOW()),
 (9002, 'seed-gov-pet-002', 'GOV24', '제주특별자치도', '제주 유기동물 입양비 지원',   '입양 초기 비용을 지원합니다.', '입양 후 6개월 이내 신청', '제주특별자치도 동물복지과', '최대 200,000원', JSON_OBJECT('test',true), 'ALL', 'https://example.test/support/2', '예산 소진 시까지',     '온라인 또는 방문', CURDATE()-INTERVAL 30 DAY, NULL,                       1, NOW())
ON DUPLICATE KEY UPDATE `summary`=VALUES(`summary`), `period_text`=VALUES(`period_text`), `synced_at`=VALUES(`synced_at`);

INSERT INTO `local_support_program_condition`
 (`program_condition_id`,`program_id`,`condition_type`,`operator`,`condition_value`,`title`,`description`,`display_order`)
VALUES
 (9001, 9001, 'REGION',         'EQ', '서울특별시',     '서울시 거주자',      '회원 주소가 서울이어야 합니다.', 1),
 (9002, 9001, 'PET_REGISTERED', 'EQ', 'Y',              '반려동물 등록 완료', '동물등록번호가 필요합니다.',     2),
 (9003, 9002, 'REGION',         'EQ', '제주특별자치도', '제주 거주자',        '회원 주소가 제주여야 합니다.',   1)
ON DUPLICATE KEY UPDATE `title`=VALUES(`title`), `description`=VALUES(`description`);

INSERT INTO `support_program_interest` (`interest_id`,`member_id`,`program_id`,`pet_id`,`status`)
VALUES (9001, 9001, 9001, 9001, 'APPLY_PAGE_OPENED')
ON DUPLICATE KEY UPDATE `status`=VALUES(`status`), `pet_id`=VALUES(`pet_id`);

INSERT INTO `donation_organization`
 (`organization_id`,`name`,`description`,`homepage_url`,`region`,`category`,`target_species`,`activity_tags`,`is_active`,`display_order`,`verified_at`)
VALUES
 (9001, '테스트 동물보호소',   '개발용 기부처',        'https://example.test/donation/1', '서울', '유기동물', 'ALL', JSON_ARRAY('구조','보호'), 1, 1, NOW()),
 (9002, '테스트 동물의료기금', '개발용 의료 기부처',   'https://example.test/donation/2', '전국', '유기동물', 'DOG', JSON_ARRAY('의료'),        1, 2, NOW()),
 (9003, '테스트 초록발자국',   '개발용 환경 기부처',   'https://example.test/donation/3', '전국', '환경',     'ALL', JSON_ARRAY('환경'),        1, 3, NOW())
ON DUPLICATE KEY UPDATE `description`=VALUES(`description`), `display_order`=VALUES(`display_order`);

INSERT INTO `donation_channel` (`channel_id`,`organization_id`,`channel_type`,`donation_type`,`channel_name`,`donation_url`,`display_order`)
VALUES
 (9001, 9001, 'IN_APP',        'ONE_TIME', '앱 내 테스트 기부', NULL,                                        1),
 (9002, 9002, 'EXTERNAL_LINK', 'ONE_TIME', '공식 후원 페이지',  'https://example.test/donation/2/support',   1),
 (9003, 9003, 'EXTERNAL_LINK', 'REGULAR',  '정기 후원 페이지',  'https://example.test/donation/3/support',   1)
ON DUPLICATE KEY UPDATE `channel_name`=VALUES(`channel_name`), `donation_url`=VALUES(`donation_url`);

INSERT INTO `donation_campaign`
 (`campaign_id`,`organization_id`,`channel_id`,`title`,`category`,`target_amount`,`raised_amount`,`participant_count`,`starts_at`,`ends_at`,`is_recommended`,`display_order`)
VALUES
 (9001, 9001, 9001, '겨울철 보호소 난방비 지원',   '유기동물', 3000000, 2046000, 312, NOW()-INTERVAL 7 DAY, NOW()+INTERVAL 8 DAY,  1, 1),
 (9002, 9002, 9002, '구조 동물 치료비 모금',       '유기동물', 3000000, 1284000, 197, NOW()-INTERVAL 7 DAY, NOW()+INTERVAL 14 DAY, 0, 2),
 (9003, 9003, 9003, '친환경 반려동물 식기 교체',   '환경',     1500000,  468000,  86, NOW()-INTERVAL 7 DAY, NOW()+INTERVAL 26 DAY, 0, 3)
ON DUPLICATE KEY UPDATE `raised_amount`=VALUES(`raised_amount`), `participant_count`=VALUES(`participant_count`), `ends_at`=VALUES(`ends_at`);

INSERT INTO `donation_setting`
 (`member_id`,`piggy_bank_enabled`,`saving_unit`,`auto_donate_enabled`,`auto_donate_organization_id`,`auto_donate_campaign_id`)
VALUES (9001, 1, 1000, 1, 9001, 9001)
ON DUPLICATE KEY UPDATE `saving_unit`=VALUES(`saving_unit`), `auto_donate_enabled`=VALUES(`auto_donate_enabled`);

INSERT INTO `member_donation_preference` (`member_id`,`organization_id`)
VALUES (9001, 9001)
ON DUPLICATE KEY UPDATE `created_at`=`created_at`;

-- 잔돈 적립 이력: 적립 대상은 owner의 DONATION 지갑(9003)
INSERT INTO `donation_roundup` (`roundup_id`,`source_txn_id`,`wallet_id`,`saving_unit`,`roundup_amount`,`status`,`completed_at`)
VALUES (9001, 9001, 9003, 1000, 600, 'COMPLETED', NOW()-INTERVAL 3 DAY)
ON DUPLICATE KEY UPDATE `roundup_amount`=VALUES(`roundup_amount`), `status`=VALUES(`status`);

-- 기부 이력: DONATION 지갑(9003)에서 3,000원 기부, 원장 거래 9003 연결
INSERT INTO `donation_history`
 (`donation_id`,`wallet_id`,`organization_id`,`campaign_id`,`channel_id`,`txn_id`,`amount`,`status`,`recipient_name`,`receipt_url`,`idempotency_key`,`completed_at`)
VALUES (9001, 9003, 9001, 9001, 9001, 9003, 3000, 'COMPLETED', '테스트 동물보호소', 'https://example.test/receipt/1', 'seed-idempotency-001', NOW()-INTERVAL 1 DAY)
ON DUPLICATE KEY UPDATE `amount`=VALUES(`amount`), `status`=VALUES(`status`), `completed_at`=VALUES(`completed_at`);

COMMIT;
