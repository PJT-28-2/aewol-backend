-- =====================================================================
-- V3: 전체 PK/FK를 UUID(VARCHAR(36)) -> BIGINT AUTO_INCREMENT로 전환
--
-- Notion API 명세 전반(예: GET /api/pets/{petId} Path Variable이
-- `petId: number`로 선언, GET /api/pets 응답이 petId:1,2...,
-- GET /api/insurance/products 응답이 productId:3,5...)이 id를 Long으로
-- 가정하고 있어, 실제 DB의 UUID PK와 어긋난다. 아직 이 스키마 위에서
-- 진행 중인 다른 백엔드 작업이 없어 지금 전환한다.
--
-- V1(22개 테이블)은 시드 데이터가 전혀 없어 컬럼 타입을 바로 ALTER한다.
-- V2의 insurance_product/insurance_product_coverage는 실데이터(24+97건)가
-- 있고 기존 UUID 값을 BIGINT로 그대로 캐스팅할 수 없으므로, 테이블을
-- 비우고 새 정수 PK로 시드 데이터를 다시 적재한다.
-- bank_master.bank_code / linked_account.bank_code는 서로게이트 키가
-- 아닌 자연키(은행 코드)라 전환 대상에서 제외한다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 타입이 바뀌는 컬럼에 걸린 FK 제약조건 전체 제거
--    (bank_code 관련 FK는 타입이 안 바뀌므로 건드리지 않는다)
-- ---------------------------------------------------------------------
ALTER TABLE `wallet` DROP FOREIGN KEY `fk_wallet_member`;
ALTER TABLE `pet` DROP FOREIGN KEY `fk_pet_member`;
ALTER TABLE `pet_document` DROP FOREIGN KEY `fk_petdoc_pet`;
ALTER TABLE `linked_account` DROP FOREIGN KEY `fk_linked_member`;
ALTER TABLE `pet_group_member`
    DROP FOREIGN KEY `fk_pgm_pet`,
    DROP FOREIGN KEY `fk_pgm_member`,
    DROP FOREIGN KEY `fk_pgm_inviter`;
ALTER TABLE `notification_setting` DROP FOREIGN KEY `fk_notif_member`;
ALTER TABLE `inquiry` DROP FOREIGN KEY `fk_inquiry_member`;
ALTER TABLE `inquiry_attachment` DROP FOREIGN KEY `fk_ia_inquiry`;
ALTER TABLE `recurring_payment`
    DROP FOREIGN KEY `fk_recurring_wallet`,
    DROP FOREIGN KEY `fk_recurring_pet`;
ALTER TABLE `transaction`
    DROP FOREIGN KEY `fk_txn_wallet`,
    DROP FOREIGN KEY `fk_txn_counter_wallet`,
    DROP FOREIGN KEY `fk_txn_pet`,
    DROP FOREIGN KEY `fk_txn_recurring`;
ALTER TABLE `donation_history`
    DROP FOREIGN KEY `fk_dh_wallet`,
    DROP FOREIGN KEY `fk_dh_organization`,
    DROP FOREIGN KEY `fk_dh_txn`;
ALTER TABLE `insurance_claim`
    DROP FOREIGN KEY `fk_claim_pet`,
    DROP FOREIGN KEY `fk_claim_member`;
ALTER TABLE `insurance_simulation` DROP FOREIGN KEY `fk_sim_pet`;
ALTER TABLE `group_purchase` DROP FOREIGN KEY `fk_gp_author`;
ALTER TABLE `group_purchase_participant`
    DROP FOREIGN KEY `fk_gpp_gp`,
    DROP FOREIGN KEY `fk_gpp_member`,
    DROP FOREIGN KEY `fk_gpp_txn`;
ALTER TABLE `insurance_product_coverage` DROP FOREIGN KEY `fk_coverage_product`;
ALTER TABLE `insurance_product_plan_tiers` DROP FOREIGN KEY `fk_tier_product`;

-- ---------------------------------------------------------------------
-- 2. V2 시드 데이터가 있는 테이블 비우기 (UUID -> BIGINT 캐스팅 불가하므로
--    비운 뒤 정수 PK로 재적재한다. plan_tiers는 원래 빈 테이블.)
-- ---------------------------------------------------------------------
TRUNCATE TABLE `insurance_product_coverage`;
TRUNCATE TABLE `insurance_product_plan_tiers`;
TRUNCATE TABLE `insurance_product`;

-- ---------------------------------------------------------------------
-- 3. PK 컬럼을 BIGINT AUTO_INCREMENT로 전환 (V1)
-- ---------------------------------------------------------------------
ALTER TABLE `member` MODIFY COLUMN `member_id` BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE `wallet` MODIFY COLUMN `wallet_id` BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE `pet` MODIFY COLUMN `pet_id` BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE `pet_document` MODIFY COLUMN `doc_id` BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE `linked_account` MODIFY COLUMN `account_id` BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE `pet_group_member` MODIFY COLUMN `pet_group_member_id` BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE `faq` MODIFY COLUMN `faq_id` BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE `inquiry` MODIFY COLUMN `inquiry_id` BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE `inquiry_attachment` MODIFY COLUMN `attachment_id` BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE `donation_organization` MODIFY COLUMN `organization_id` BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE `recurring_payment` MODIFY COLUMN `recurring_id` BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE `transaction` MODIFY COLUMN `txn_id` BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE `donation_history` MODIFY COLUMN `donation_id` BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE `insurance_claim` MODIFY COLUMN `claim_id` BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE `insurance_simulation` MODIFY COLUMN `sim_id` BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE `activity_log` MODIFY COLUMN `log_id` BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE `group_purchase` MODIFY COLUMN `gp_id` BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE `group_purchase_participant` MODIFY COLUMN `participant_id` BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE `local_support_program` MODIFY COLUMN `program_id` BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE `emergency_hospital` MODIFY COLUMN `hospital_id` BIGINT NOT NULL AUTO_INCREMENT;

-- notification_setting.member_id는 PK이자 member.member_id를 그대로 따르는
-- 1:1 컬럼이라 자체 AUTO_INCREMENT를 갖지 않는다.
ALTER TABLE `notification_setting` MODIFY COLUMN `member_id` BIGINT NOT NULL;

-- PK 컬럼을 BIGINT AUTO_INCREMENT로 전환 (V2, 비운 뒤 전환)
ALTER TABLE `insurance_product` MODIFY COLUMN `product_id` BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE `insurance_product_coverage` MODIFY COLUMN `coverage_id` BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE `insurance_product_plan_tiers` MODIFY COLUMN `tier_id` BIGINT NOT NULL AUTO_INCREMENT;

-- ---------------------------------------------------------------------
-- 4. FK 컬럼(자식 테이블)을 BIGINT로 전환 — 원본 NULL/NOT NULL 그대로 유지
-- ---------------------------------------------------------------------
ALTER TABLE `wallet` MODIFY COLUMN `member_id` BIGINT NOT NULL;
ALTER TABLE `pet` MODIFY COLUMN `member_id` BIGINT NOT NULL;
ALTER TABLE `pet_document` MODIFY COLUMN `pet_id` BIGINT NOT NULL;
ALTER TABLE `linked_account` MODIFY COLUMN `member_id` BIGINT NOT NULL;
ALTER TABLE `pet_group_member`
    MODIFY COLUMN `pet_id` BIGINT NOT NULL,
    MODIFY COLUMN `member_id` BIGINT NULL,
    MODIFY COLUMN `invited_by` BIGINT NOT NULL;
ALTER TABLE `inquiry` MODIFY COLUMN `member_id` BIGINT NOT NULL;
ALTER TABLE `inquiry_attachment` MODIFY COLUMN `inquiry_id` BIGINT NOT NULL;
ALTER TABLE `recurring_payment`
    MODIFY COLUMN `wallet_id` BIGINT NOT NULL,
    MODIFY COLUMN `pet_id` BIGINT NULL;
ALTER TABLE `transaction`
    MODIFY COLUMN `wallet_id` BIGINT NOT NULL,
    MODIFY COLUMN `counter_wallet_id` BIGINT NULL,
    MODIFY COLUMN `pet_id` BIGINT NULL,
    MODIFY COLUMN `recurring_id` BIGINT NULL;
ALTER TABLE `donation_history`
    MODIFY COLUMN `wallet_id` BIGINT NOT NULL,
    MODIFY COLUMN `organization_id` BIGINT NOT NULL,
    MODIFY COLUMN `txn_id` BIGINT NULL;
ALTER TABLE `insurance_claim`
    MODIFY COLUMN `pet_id` BIGINT NOT NULL,
    MODIFY COLUMN `member_id` BIGINT NOT NULL;
ALTER TABLE `insurance_simulation` MODIFY COLUMN `pet_id` BIGINT NOT NULL;
ALTER TABLE `group_purchase` MODIFY COLUMN `member_id` BIGINT NOT NULL;
ALTER TABLE `group_purchase_participant`
    MODIFY COLUMN `gp_id` BIGINT NOT NULL,
    MODIFY COLUMN `member_id` BIGINT NOT NULL,
    MODIFY COLUMN `txn_id` BIGINT NULL;

-- FK 제약조건은 없지만 논리적으로 wallet_id/target_id를 참조하므로 함께 전환
ALTER TABLE `activity_log`
    MODIFY COLUMN `wallet_id` BIGINT NOT NULL,
    MODIFY COLUMN `target_id` BIGINT NULL;

ALTER TABLE `insurance_product_coverage` MODIFY COLUMN `product_id` BIGINT NOT NULL;
ALTER TABLE `insurance_product_plan_tiers` MODIFY COLUMN `product_id` BIGINT NOT NULL;

-- ---------------------------------------------------------------------
-- 5. FK 제약조건 재생성 (원본과 동일한 이름/구성)
-- ---------------------------------------------------------------------
ALTER TABLE `wallet` ADD CONSTRAINT `fk_wallet_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`);
ALTER TABLE `pet` ADD CONSTRAINT `fk_pet_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`);
ALTER TABLE `pet_document` ADD CONSTRAINT `fk_petdoc_pet` FOREIGN KEY (`pet_id`) REFERENCES `pet`(`pet_id`);
ALTER TABLE `linked_account` ADD CONSTRAINT `fk_linked_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`);
ALTER TABLE `pet_group_member`
    ADD CONSTRAINT `fk_pgm_pet` FOREIGN KEY (`pet_id`) REFERENCES `pet`(`pet_id`),
    ADD CONSTRAINT `fk_pgm_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`),
    ADD CONSTRAINT `fk_pgm_inviter` FOREIGN KEY (`invited_by`) REFERENCES `member`(`member_id`);
ALTER TABLE `notification_setting` ADD CONSTRAINT `fk_notif_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`);
ALTER TABLE `inquiry` ADD CONSTRAINT `fk_inquiry_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`);
ALTER TABLE `inquiry_attachment` ADD CONSTRAINT `fk_ia_inquiry` FOREIGN KEY (`inquiry_id`) REFERENCES `inquiry`(`inquiry_id`);
ALTER TABLE `recurring_payment`
    ADD CONSTRAINT `fk_recurring_wallet` FOREIGN KEY (`wallet_id`) REFERENCES `wallet`(`wallet_id`),
    ADD CONSTRAINT `fk_recurring_pet` FOREIGN KEY (`pet_id`) REFERENCES `pet`(`pet_id`);
ALTER TABLE `transaction`
    ADD CONSTRAINT `fk_txn_wallet` FOREIGN KEY (`wallet_id`) REFERENCES `wallet`(`wallet_id`),
    ADD CONSTRAINT `fk_txn_counter_wallet` FOREIGN KEY (`counter_wallet_id`) REFERENCES `wallet`(`wallet_id`),
    ADD CONSTRAINT `fk_txn_pet` FOREIGN KEY (`pet_id`) REFERENCES `pet`(`pet_id`),
    ADD CONSTRAINT `fk_txn_recurring` FOREIGN KEY (`recurring_id`) REFERENCES `recurring_payment`(`recurring_id`);
ALTER TABLE `donation_history`
    ADD CONSTRAINT `fk_dh_wallet` FOREIGN KEY (`wallet_id`) REFERENCES `wallet`(`wallet_id`),
    ADD CONSTRAINT `fk_dh_organization` FOREIGN KEY (`organization_id`) REFERENCES `donation_organization`(`organization_id`),
    ADD CONSTRAINT `fk_dh_txn` FOREIGN KEY (`txn_id`) REFERENCES `transaction`(`txn_id`);
ALTER TABLE `insurance_claim`
    ADD CONSTRAINT `fk_claim_pet` FOREIGN KEY (`pet_id`) REFERENCES `pet`(`pet_id`),
    ADD CONSTRAINT `fk_claim_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`);
ALTER TABLE `insurance_simulation` ADD CONSTRAINT `fk_sim_pet` FOREIGN KEY (`pet_id`) REFERENCES `pet`(`pet_id`);
ALTER TABLE `group_purchase` ADD CONSTRAINT `fk_gp_author` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`);
ALTER TABLE `group_purchase_participant`
    ADD CONSTRAINT `fk_gpp_gp` FOREIGN KEY (`gp_id`) REFERENCES `group_purchase`(`gp_id`),
    ADD CONSTRAINT `fk_gpp_member` FOREIGN KEY (`member_id`) REFERENCES `member`(`member_id`),
    ADD CONSTRAINT `fk_gpp_txn` FOREIGN KEY (`txn_id`) REFERENCES `transaction`(`txn_id`);
ALTER TABLE `insurance_product_coverage` ADD CONSTRAINT `fk_coverage_product` FOREIGN KEY (`product_id`) REFERENCES `insurance_product`(`product_id`);
ALTER TABLE `insurance_product_plan_tiers` ADD CONSTRAINT `fk_tier_product` FOREIGN KEY (`product_id`) REFERENCES `insurance_product`(`product_id`);

-- ---------------------------------------------------------------------
-- 6. insurance_product / insurance_product_coverage 시드 데이터 재적재
--    (product_id/coverage_id는 명시하지 않고 AUTO_INCREMENT로 1부터 순서대로
--    부여 — 아래 INSERT 순서가 곧 새 product_id 1~24가 된다)
-- ---------------------------------------------------------------------
INSERT INTO `insurance_product`
    (`species`, `company_name`, `product_name`, `premium_monthly_equiv`, `premium_period`,
     `join_age_min`, `join_age_max`, `age_basis`, `join_type`, `product_url`, `remark`)
VALUES
    ('DOG', '삼성화재', '무배당 삼성화재 다이렉트 착한펫보험(강아지)(2601.6)(자동갱신형)', 6193.00, 'MONTHLY', 0, 80, 'OWNER', '온라인가입', 'http://direct.samsungfire.com/CR_MyAnycarWeb/overture_index.jsp?OTK=PE2210OB100', '보장(담보) 총1종 <br/> 5년만기/5년납 <br/> 남자, 40세, 상해1급 <br/> 말티즈 0세 가입'),                                                             -- 1
    ('DOG', 'DB손보', '(무)다이렉트 펫블리 반려견보험2607(CM)', 29921.00, 'MONTHLY', 0, 80, 'OWNER', '온라인가입', 'https://www.directdb.co.kr/ltm/prd/cpnm/custInfoView.do?searchPdcCd=31054&searchPdcTrtHistCd=00&pdcDvcd=l_pet_dog', '보장(담보) 총5종 <br/> 20년만기/20년납 <br/> 말티즈 0세 가입(1년만기 재가입)'),                     -- 2
    ('DOG', '농협손보', '(무) NH다이렉트펫앤미든든보험[1종:강아지]2604', 33801.00, 'MONTHLY', 0, 80, 'OWNER', '모바일가입', 'https://www.nhfire.co.kr/product/retrieveProduct.nhfire?pdtCd=D314512', '보장(담보) 총 6종, 20년만기/20년납, 남자 40세 기준'),                                        -- 3
    ('DOG', '농협손보', '(무) NH다이렉트펫앤미든든보험[3종:재가입용]2604', 33801.00, 'MONTHLY', 0, 80, 'OWNER', '모바일가입', 'https://www.nhfire.co.kr/product/retrieveProduct.nhfire?pdtCd=D314512', '보장(담보) 총 6종, 20년만기/20년납, 남자 40세 기준'),                                  -- 4
    ('DOG', '메리츠화재', '(무) 펫퍼민트 Puppy&Family보험 다이렉트2604', 33971.00, 'MONTHLY', 18, 80, 'OWNER', '온라인가입', 'https://store.meritzfire.com/pet/product.do#!/', '보장(담보) 총 8종 <br/> 남자 40세, 상해1급, 20년만기/전기납 <br/>  반려견 0세, C그룹, 70%보장형 자기부담금 3만원 기준, 1년만기/전기납  <br/> 반려견 0세, C그룹, 5년만기/전기납 <br/> 순수보장형'), -- 5
    ('DOG', 'DB손보', '(무)다이렉트 펫블리 반려견 의료비보험2605(CM)(재가입용)', 35727.00, 'MONTHLY', 1, 19, 'PET', '온라인가입', 'https://www.directdb.co.kr/ltm/prd/cpnm/custInfoView.do?searchPdcCd=31054&searchPdcTrtHistCd=00&pdcDvcd=l_pet_dog', '보장(담보) 총5종 <br/> 말티즈 1세 가입(1년만기 재가입) <br/> 월납 기준'),               -- 6
    ('DOG', '카카오페이손해보험', '무배당 펫보험', 35786.00, 'MONTHLY', 0, 3, 'PET', '모바일가입', 'https://kakaopayinscorp.co.kr/products/PET?dc=DMA100&utm_source=partner_damoa&utm_medium=b2c_damoa&utm_campaign=damoa_pet&utm_content=pet', '40세 남자, 20년만기 전기납, 반려견 말티즈 0세, 갱신형 특별약관 3년만기 전기납, 재가입형 특별약관 1년만기 전기납, 월납'), -- 7
    ('DOG', '현대해상', '(무)현대해상다이렉트굿앤굿우리펫보험(Hi2601) 1종<반려견>_스탠다드플랜', 41760.00, 'MONTHLY', 19, 80, 'OWNER', '온라인가입', 'https://direct.hi.co.kr/service.do?m=108256981a&petType=D&utm_source=damoa&utm_medium=partner&utm_campaign=pet_pc&HDMS1=partner&HDMS2=damoa&HDMS3=damoa&HDMS4=pet_pc&inpath=e_sure_market', '보장(담보) 총14종'), -- 8
    ('DOG', 'KB손보', 'KB 다이렉트 금쪽같은 펫보험(강아지)(무배당)(26.07)', 42903.00, 'MONTHLY', 0, 80, 'OWNER', '온라인가입', 'https://direct.kbinsure.co.kr/home/#/GL/LPD/LT_CM0101M/', '보장(담보) 총 7종<br/>20년만기/20년납/펫 가입(1~3년갱신)<br/>순수보장형'),                        -- 9
    ('DOG', '현대해상', '(무)현대해상다이렉트굿앤굿우리펫보험(재가입용)(Hi2605) 1종<반려견>_표준플랜', 44410.00, 'MONTHLY', 1, 19, 'OWNER', '온라인가입', 'https://mdirect.hi.co.kr/service.do?m=5e5408d724&utm_source=damoa&utm_medium=partner&utm_campaign=pet_renew_mo&HDMS1=partner&HDMS2=damoa&HDMS3=damoa&HDMS4=pet_renew_mo&inpath=e_sure_market', '보장(담보) 총9종'), -- 10
    ('DOG', 'KB손보', 'KB 다이렉트 금쪽같은 펫보험(강아지)(재가입용)(무배당)(26.05)', 48914.00, 'MONTHLY', 1, 19, 'PET', '온라인가입', 'ㅡ', '보장(담보) 총 2종<br/>1년만기/전기납/월납/반려동물 1세<br/>연령은 반려동물의 연령을 의미<br/>순수보장형'),                                    -- 11
    ('DOG', '롯데손보', '댕댕이랑 여행갈땐보험', 502.50, 'ANNUAL', 0, 20, 'PET', '온라인가입', 'https://alice.lotteins.co.kr', '보험기간 1년'),                                                                                              -- 12
    ('DOG', '마이브라운', '강아지만 생각하는 강아지보험', 27595.00, 'MONTHLY', 0, 10, 'PET', '온라인가입', 'www.mybrown.co.kr', '월납보험료'),                                                                                                 -- 13
    ('CAT', '삼성화재', '무배당 삼성화재 다이렉트 반려묘보험(2601.7)(자동갱신형)', 21541.00, 'MONTHLY', 0, 80, 'OWNER', '온라인가입', 'http://direct.samsungfire.com/CR_MyAnycarWeb/overture_index.jsp?OTK=PE2304OB100', '보장(담보) 총6종 <br/> 3,5년만기/전기납 <br/> 남자, 40세, 상해1급 <br/> 코리안숏헤어 0세 가입'), -- 14
    ('CAT', '카카오페이손해보험', '무배당 펫보험', 28322.00, 'MONTHLY', 0, 3, 'PET', '모바일가입', 'https://kakaopayinscorp.co.kr/products/PET?dc=DMA100&utm_source=partner_damoa&utm_medium=b2c_damoa&utm_campaign=damoa_pet&utm_content=pet', '40세 남자, 20년만기 전기납, 반려묘 코리안숏헤어 0세, 갱신형 특별약관 3년만기 전기납, 재가입형 특별약관 1년만기 전기납, 월납'), -- 15
    ('CAT', '현대해상', '(무)현대해상다이렉트굿앤굿우리펫보험(재가입용)(Hi2605) 2종<반려묘>_표준플랜', 28650.00, 'MONTHLY', 1, 19, 'OWNER', '온라인가입', 'https://mdirect.hi.co.kr/service.do?m=5e5408d724&utm_source=damoa&utm_medium=partner&utm_campaign=cat_renew_mo&HDMS1=partner&HDMS2=damoa&HDMS3=damoa&HDMS4=cat_renew_mo&inpath=e_sure_market', '보장(담보) 총5종'), -- 16
    ('CAT', '농협손보', '(무) NH다이렉트펫앤미든든보험[2종:고양이]2604', 29336.00, 'MONTHLY', 0, 80, 'OWNER', '모바일가입', 'https://www.nhfire.co.kr/product/retrieveProduct.nhfire?pdtCd=D314522', '보장(담보) 총 5종, 20년만기/20년납, 남자 40세 기준'),                                       -- 17
    ('CAT', 'KB손보', 'KB 다이렉트 금쪽같은 펫보험(고양이)(재가입용)(무배당)(26.05)', 31815.00, 'MONTHLY', 1, 19, 'PET', '온라인가입', 'ㅡ', '보장(담보) 총 2종<br/>1년만기/전기납/월납/반려동물 1세<br/>연령은 반려동물의 연령을 의미<br/>순수보장형'),                                     -- 18
    ('CAT', '메리츠화재', '(무) 펫퍼민트 Cat&Family보험 다이렉트2604', 32432.00, 'MONTHLY', 18, 80, 'OWNER', '온라인가입', 'https://store.meritzfire.com/pet/product-cat.do#!/', '보장(담보) 총 7종 <br/> 남자 40세, 상해1급, 20년만기/전기납 <br/> 반려묘 70%보장형 자기부담금 3만원, 1년만기/전기납 <br/> 순수보장형'), -- 19
    ('CAT', 'DB손보', '(무)다이렉트 펫블리 반려묘 의료비보험2605(CM)(재가입용)', 32522.00, 'MONTHLY', 1, 19, 'PET', '온라인가입', 'https://www.directdb.co.kr/ltm/prd/cpnmcat/custInfoView.do?searchPdcCd=31067&searchPdcTrtHistCd=00&pdcDvcd=l_pet_cat', '보장(담보) 총5종 <br/> 코리안숏헤어 1세 가입 (1년만기재가입 : 반려동물의료비) <br/> 월납 기준'), -- 20
    ('CAT', '현대해상', '(무)현대해상다이렉트굿앤굿우리펫보험(Hi2601) 2종<반려묘>_스탠다드플랜', 32910.00, 'MONTHLY', 19, 80, 'OWNER', '온라인가입', 'https://direct.hi.co.kr/service.do?m=108256981a&petType=C&utm_source=damoa&utm_medium=partner&utm_campaign=cat_pc&HDMS1=partner&HDMS2=damoa&HDMS3=damoa&HDMS4=cat_pc&inpath=e_sure_market', '보장(담보) 총9종'), -- 21
    ('CAT', 'DB손보', '(무)다이렉트 펫블리 반려묘보험2607(CM)', 34692.00, 'MONTHLY', 0, 80, 'OWNER', '온라인가입', 'https://www.directdb.co.kr/ltm/prd/cpnmcat/custInfoView.do?searchPdcCd=31067&searchPdcTrtHistCd=00&pdcDvcd=l_pet_cat', '보장(담보) 총5종 <br/> 20년만기/20년납 <br/> 코리안숏헤어 0세 가입(5년 갱신 : 장례지원비담보 / 1년만기재가입 : 반려동물의료비)'), -- 22
    ('CAT', 'KB손보', 'KB 다이렉트 금쪽같은 펫보험(고양이)(무배당)(26.07)', 35158.00, 'MONTHLY', 0, 80, 'OWNER', '온라인가입', 'https://direct.kbinsure.co.kr/home/#/GL/LPC/LT_CM0101M/', '보장(담보) 총 6종<br/>20년만기/20년납/펫 가입(1~3년갱신)<br/>순수보장형'),                        -- 23
    ('CAT', '마이브라운', '고양이만 생각하는 고양이보험', 30843.00, 'MONTHLY', 0, 10, 'PET', '온라인가입', 'www.mybrown.co.kr', '월납보험료');                                                                                                -- 24

INSERT INTO `insurance_product_coverage` (`product_id`, `coverage_name`, `coverage_amount`) VALUES
    (1, '상해 후유장해(80%이상)', '1000만원'),
    (2, '반려동물의료비Ⅵ(슬관절/고관절탈구포함(1년이후))(실손)', '1000만원'),
    (2, '반려동물의료비Ⅵ 치과및구강질환 확장보장(실손)', NULL),
    (2, '반려동물의료비Ⅵ 특정피부약물치료 확장보장(실손)', NULL),
    (2, '반려동물 장례지원비(개)(갱신형)', '30만원'),
    (3, '반려동물 치료비Ⅲ(강아지)', '1000만원'),
    (3, '반려동물 치료비Ⅲ 구강질환 확장보장(강아지)', NULL),
    (3, '반려동물 치료비Ⅲ 피부질환 확장보장(강아지)', NULL),
    (3, '(갱신형)반려동물 배상책임(강아지)', '500만원'),
    (3, '(갱신형)반려동물 사망(강아지)', '15만원'),
    (4, '반려동물 치료비Ⅲ(강아지)', '1000만원'),
    (4, '반려동물 치료비Ⅲ 구강질환 확장보장(강아지)', NULL),
    (4, '반려동물 치료비Ⅲ 피부질환 확장보장(강아지)', NULL),
    (4, '(갱신형)반려동물 배상책임(강아지)', '500만원'),
    (4, '(갱신형)반려동물 사망(강아지)', '15만원'),
    (5, '펫퍼민트 반려견 통원의료비보장', '500만원 한도'),
    (5, '펫퍼민트 반려견 입원의료비보장', NULL),
    (5, '갱신형 펫퍼민트 반려견 배상책임보장', '1000만원 한도'),
    (6, '반려동물의료비Ⅵ(슬관절/고관절탈구포함(1년이후))(실손)', '1000만원'),
    (6, '반려동물의료비Ⅵ 치과및구강질환 확장보장(실손)', NULL),
    (6, '반려동물의료비Ⅵ 특정피부약물치료 확장보장(실손)', NULL),
    (6, '반려동물의료비Ⅵ(MRI,CT,내시경 검사시 보장금액확대)(연간1회한)(실손)(50%보상)', '100만원'),
    (7, '반려견 의료비(수술, 입원, 통원)', '4000만원'),
    (7, '반려견 배상책임보장(갱신형)', '500만원'),
    (7, '반려견 무지개다리위로금(갱신형)', '30만원'),
    (8, '반려동물돌봄비(상해입원일당(1-10일))', '1만원'),
    (8, '응급실내원진료비(응급)', '10만원'),
    (8, '반려견입원의료비Ⅱ', '15백만원'),
    (8, '반려견통원의료비Ⅱ', NULL),
    (8, '반려견입원의료비확장보장(치과및구강질환)Ⅱ', NULL),
    (8, '반려견통원의료비확장보장(치과및구강질환)Ⅱ', NULL),
    (8, '반려견입원의료비확장보장(슬관절,고관절 탈구)', NULL),
    (8, '반려견통원의료비확장보장(슬관절,고관절 탈구)', NULL),
    (8, '반려견의료비확장보장(MRI/CT)(연간1회한)', '100만원'),
    (8, '반려견의료비확장보장(특정처치(이물제거)(연간2회한)', '200만원'),
    (8, '반려견의료비확장보장(특정약물치료)(연간12회한)담보', '10만원'),
    (8, '반려견사망위로금(갱신형)', '30만원'),
    (8, '반려견배상책임Ⅱ(갱신형)', '1천만원'),
    (9, '반려동물 위탁비용(반려인 상해입원 1일이상 180일한도)(실손)(강아지)', '5만원'),
    (9, '반려동물의료비Ⅱ(강아지)', '2000만원'),
    (9, '반려동물의료비확장보장Ⅱ(주요치료)(강아지)', '1000만원'),
    (9, '반려동물배상책임(강아지)(갱신형)', NULL),
    (9, '반려동물장례비용지원금(실손)(30일면책)(강아지)(갱신형)', '100만원'),
    (9, '무지개다리위로금(강아지, 사망)(갱신형)', '30만원'),
    (10, '기본계약(반려견통원의료비Ⅱ(재가입용))', NULL),
    (10, '반려견입원의료비확장보장(슬관절,고관절 탈구)(재가입용)', NULL),
    (10, '반려견통원의료비확장보장(슬관절,고관절 탈구)(재가입용)', NULL),
    (10, '반려견의료비확장보장(MRI/CT)(연간1회한)(재가입용)', '100만원'),
    (10, '반려견의료비확장보장(특정처치(이물제거))(연간2회한)(재가입용)', '200만원'),
    (10, '반려견입원의료비확장보장(치과및구강질환)Ⅱ(재가입용)', '15백만원'),
    (10, '반려견통원의료비확장보장(치과및구강질환)Ⅱ(재가입용)', NULL),
    (10, '반려견의료비확장보장(특정약물치료)(연간12회한)(재가입용)', '10만원'),
    (11, '반려동물의료비확장보장Ⅱ(주요치료)(강아지)', '1000만원'),
    (12, '반려동물 위탁비용(반려인 상해입원 1일이상 180일 한도)', '5만원'),
    (12, '반려동물 양육자금(일반상해사망)', '500만원'),
    (13, '수술 및 특정고액치료비', '200만원'),
    (13, '반려동물 배상책임', '3000만원'),
    (13, '무지개별 여행비', '30만원'),
    (14, '반려묘의료비(치과및구강질환포함)(50%보상형)(재가입형)', '1일당 10만원/연간 1000만원'),
    (14, '반려묘 수술비(치과및구강질환포함) 확대보장(50%보상형)(재가입형)', '200만원'),
    (14, '반려묘 의료비 확대보장(이물제거 특정처치)(연간2회한)(50%보상형)(재가입형)', NULL),
    (14, '반려묘의료비확대보장(MRI,CT)(연간1회한)(50%보상형)(재가입형)', '100만원'),
    (14, '[갱신]반려묘사망위로금', '30만원'),
    (15, '반려묘 의료비(수술, 입원, 통원)', '4000만원'),
    (15, '반려묘 무지개다리위로금(갱신형)', '30만원'),
    (16, '기본계약(반려묘통원의료비Ⅱ(재가입용))', NULL),
    (16, '반려묘의료비확장보장(MRI/CT)(연간1회한)(재가입용)', '100만원'),
    (16, '반려묘입원의료비확장보장(치과및구강질환)Ⅱ(재가입용)', '15백만원'),
    (16, '반려묘통원의료비확장보장(치과및구강질환)Ⅱ(재가입용)', NULL),
    (17, '반려동물 치료비Ⅲ(고양이)', '1000만원'),
    (17, '반려동물 치료비Ⅲ 구강질환 확장보장(고양이)', NULL),
    (17, '반려동물 치료비Ⅲ 비뇨기질환 확장보장(고양이)', NULL),
    (17, '(갱신형)반려동물 사망(고양이)', '15만원'),
    (18, '반려동물의료비확장보장Ⅱ(주요치료)(고양이)', '1000만원'),
    (19, '펫퍼민트 반려묘 통원의료비보장', '500만원 한도'),
    (19, '펫퍼민트 반려묘 입원의료비보장', NULL),
    (20, '반려동물의료비Ⅵ 치과및구강질환 확장보장Ⅱ(고양이)(실손)', NULL),
    (20, '반려동물의료비Ⅵ(고양이)(MRI,CT,내시경 검사시 보장금액확대)(연간1회한)', '보험가입금액(100만원)'),
    (21, '반려동물돌봄비(상해입원일당(1-10일))', '1만원'),
    (21, '응급실내원진료비(응급)', '10만원'),
    (21, '반려묘입원의료비Ⅱ', '15백만원'),
    (21, '반려묘통원의료비Ⅱ', NULL),
    (21, '반려묘입원의료비확장보장(치과및구강질환)Ⅱ담보', NULL),
    (21, '반려묘통원의료비확장보장(치과및구강질환)Ⅱ담보', NULL),
    (21, '반려묘의료비확장보장(MRI/CT)(연간1회한)', '100만원'),
    (21, '반려묘사망위로금(갱신형)', '30만원'),
    (22, '반려동물의료비Ⅵ(고양이)(실손)', '보험가입금액(1000만원)'),
    (22, '반려동물의료비Ⅵ 치과및구강질환 확장보장Ⅱ(고양이)(실손)', NULL),
    (22, '반려동물의료비Ⅵ(고양이)(MRI,CT,내시경 검사시 보장금액확대)(연간1회한)', '보험가입금액(100만원)'),
    (22, '반려동물 장례지원비(고양이)(갱신형)', '보험가입금액(30만원)'),
    (23, '반려동물 위탁비용(반려인 상해입원 1일이상 180일한도)(실손)(고양이)(갱신형)', '5만원'),
    (23, '반려동물의료비Ⅱ(고양이)', '2000만원'),
    (23, '반려동물의료비확장보장Ⅱ(주요치료)(고양이)', '1000만원'),
    (23, '무지개다리위로금(고양이, 사망)(갱신형)', '30만원'),
    (23, '반려동물장례비용지원금(실손)(30일면책)(고양이)(갱신형)', '70만원'),
    (24, '수술 및 특정고액치료비', '200만원'),
    (24, '무지개별 여행비', '30만원');
