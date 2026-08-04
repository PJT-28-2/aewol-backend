-- =====================================================================
-- V2: 반려동물보험 상품 카탈로그 + 시뮬레이션 스키마
--
-- 출처: Desktop/펫보험/*.xls (강아지 2개, 고양이 2개 파일)
-- 생성: aewol-backend/scripts/insurance-catalog-import/parse.mjs (1회성 Node 스크립트)
-- 총 상품 수: 24 (DOG=13, CAT=11), 총 보장 라인 수: 97
--
-- Notion API 명세(POST /api/insurance/simulations, GET /api/insurance/products,
-- 2026-07-30 업데이트) 기준 컬럼/테이블을 함께 반영한다.
-- reimbursement_*, age_subject_confidence, medical_category, plan_tiers 등
-- 신규 필드는 이번 커밋에서 근거가 확인된 값만 채우고 나머지는 NULL/기본값으로
-- 두며, 실값 보강은 후속 이슈에서 진행한다.
--
-- 재적재 시 이 파일을 재생성하려면 parse.mjs를 다시 실행한다 (런타임 재적재 없음).
-- =====================================================================

CREATE TABLE IF NOT EXISTS `insurance_product` (
    `product_id`               VARCHAR(36)   NOT NULL,
    `species`                  VARCHAR(10)   NOT NULL COMMENT 'DOG / CAT',
    `company_name`             VARCHAR(50)   NOT NULL,
    `product_name`             VARCHAR(200)  NOT NULL,
    `premium_monthly_equiv`    DECIMAL(15,2) NOT NULL,
    `premium_period`           VARCHAR(10)   NOT NULL DEFAULT 'MONTHLY' COMMENT 'MONTHLY / ANNUAL / UNKNOWN',
    `join_age_min`             INT           NULL,
    `join_age_max`             INT           NULL,
    `age_basis`                VARCHAR(10)   NOT NULL COMMENT 'PET / OWNER',
    `age_subject_confidence`   VARCHAR(20)   NULL COMMENT 'CONFIRMED / ASSUMED / UNKNOWN_DEFAULT',
    `join_type`                VARCHAR(20)   NULL,
    `product_url`              VARCHAR(500)  NULL,
    `phone`                    VARCHAR(30)   NULL,
    `reimbursement_structure`  VARCHAR(30)   NULL COMMENT 'FIXED / BUNDLED_TIER / INDEPENDENT_SELECTABLE / UNKNOWN',
    `reimbursement_rate_pct`   INT           NULL,
    `reimbursement_confidence` VARCHAR(30)   NULL COMMENT 'CONFIRMED_OWN_COVERAGE_NAME / ASSUMED_FROM_RESEARCH / UNVERIFIED',
    `regulatory_cap_warning`   TEXT          NULL,
    `remark`                   TEXT          NULL,
    `created_at`               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`product_id`),
    KEY `idx_product_species_age` (`species`, `age_basis`, `join_age_min`, `join_age_max`, `premium_monthly_equiv`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `insurance_product_coverage` (
    `coverage_id`      VARCHAR(36)   NOT NULL,
    `product_id`       VARCHAR(36)   NOT NULL,
    `coverage_name`    VARCHAR(200)  NOT NULL,
    `coverage_amount`  VARCHAR(50)   NULL,
    `medical_category` VARCHAR(20)   NULL COMMENT 'DENTAL / URINARY / FOREIGN_BODY / JOINT / SKIN / DIGESTIVE / OTHER / NONE',
    PRIMARY KEY (`coverage_id`),
    CONSTRAINT `fk_coverage_product` FOREIGN KEY (`product_id`) REFERENCES `insurance_product`(`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `insurance_product_plan_tiers` (
    `tier_id`                VARCHAR(36)   NOT NULL,
    `product_id`              VARCHAR(36)   NOT NULL,
    `tier_name`                VARCHAR(50)   NOT NULL,
    `reimbursement_rate_pct`  INT           NULL,
    `is_reference_tier`        TINYINT(1)    NOT NULL DEFAULT 0,
    `premium_krw`              DECIMAL(15,2) NULL,
    PRIMARY KEY (`tier_id`),
    CONSTRAINT `fk_tier_product` FOREIGN KEY (`product_id`) REFERENCES `insurance_product`(`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `insurance_product`
    (`product_id`, `species`, `company_name`, `product_name`, `premium_monthly_equiv`, `premium_period`,
     `join_age_min`, `join_age_max`, `age_basis`, `join_type`, `product_url`, `remark`)
VALUES
    ('ba916088-b4ff-4e69-8b02-68df9980ed12', 'DOG', '삼성화재', '무배당 삼성화재 다이렉트 착한펫보험(강아지)(2601.6)(자동갱신형)', 6193.00, 'MONTHLY', 0, 80, 'OWNER', '온라인가입', 'http://direct.samsungfire.com/CR_MyAnycarWeb/overture_index.jsp?OTK=PE2210OB100', '보장(담보) 총1종 <br/> 5년만기/5년납 <br/> 남자, 40세, 상해1급 <br/> 말티즈 0세 가입'),
    ('a9c71c30-a2d7-42ce-85c7-c61061a4a3fd', 'DOG', 'DB손보', '(무)다이렉트 펫블리 반려견보험2607(CM)', 29921.00, 'MONTHLY', 0, 80, 'OWNER', '온라인가입', 'https://www.directdb.co.kr/ltm/prd/cpnm/custInfoView.do?searchPdcCd=31054&searchPdcTrtHistCd=00&pdcDvcd=l_pet_dog', '보장(담보) 총5종 <br/> 20년만기/20년납 <br/> 말티즈 0세 가입(1년만기 재가입)'),
    ('25638eb5-ed78-48cf-8610-1508d30f061f', 'DOG', '농협손보', '(무) NH다이렉트펫앤미든든보험[1종:강아지]2604', 33801.00, 'MONTHLY', 0, 80, 'OWNER', '모바일가입', 'https://www.nhfire.co.kr/product/retrieveProduct.nhfire?pdtCd=D314512', '보장(담보) 총 6종, 20년만기/20년납, 남자 40세 기준'),
    ('6f6eaea1-c843-474a-bfe8-5636dde07ace', 'DOG', '농협손보', '(무) NH다이렉트펫앤미든든보험[3종:재가입용]2604', 33801.00, 'MONTHLY', 0, 80, 'OWNER', '모바일가입', 'https://www.nhfire.co.kr/product/retrieveProduct.nhfire?pdtCd=D314512', '보장(담보) 총 6종, 20년만기/20년납, 남자 40세 기준'),
    ('2e3de3a3-1cbb-4ce3-970f-bb37fcb0961c', 'DOG', '메리츠화재', '(무) 펫퍼민트 Puppy&Family보험 다이렉트2604', 33971.00, 'MONTHLY', 18, 80, 'OWNER', '온라인가입', 'https://store.meritzfire.com/pet/product.do#!/', '보장(담보) 총 8종 <br/> 남자 40세, 상해1급, 20년만기/전기납 <br/>  반려견 0세, C그룹, 70%보장형 자기부담금 3만원 기준, 1년만기/전기납  <br/> 반려견 0세, C그룹, 5년만기/전기납 <br/> 순수보장형'),
    ('fc4d9bae-4968-491f-98eb-da7d15c20f06', 'DOG', 'DB손보', '(무)다이렉트 펫블리 반려견 의료비보험2605(CM)(재가입용)', 35727.00, 'MONTHLY', 1, 19, 'PET', '온라인가입', 'https://www.directdb.co.kr/ltm/prd/cpnm/custInfoView.do?searchPdcCd=31054&searchPdcTrtHistCd=00&pdcDvcd=l_pet_dog', '보장(담보) 총5종 <br/> 말티즈 1세 가입(1년만기 재가입) <br/> 월납 기준'),
    ('d1027ae7-9862-426a-87ea-c776cfb13b14', 'DOG', '카카오페이손해보험', '무배당 펫보험', 35786.00, 'MONTHLY', 0, 3, 'PET', '모바일가입', 'https://kakaopayinscorp.co.kr/products/PET?dc=DMA100&utm_source=partner_damoa&utm_medium=b2c_damoa&utm_campaign=damoa_pet&utm_content=pet', '40세 남자, 20년만기 전기납, 반려견 말티즈 0세, 갱신형 특별약관 3년만기 전기납, 재가입형 특별약관 1년만기 전기납, 월납'),
    ('a8de7471-0a40-4b89-997c-5e004f9aeb65', 'DOG', '현대해상', '(무)현대해상다이렉트굿앤굿우리펫보험(Hi2601) 1종<반려견>_스탠다드플랜', 41760.00, 'MONTHLY', 19, 80, 'OWNER', '온라인가입', 'https://direct.hi.co.kr/service.do?m=108256981a&petType=D&utm_source=damoa&utm_medium=partner&utm_campaign=pet_pc&HDMS1=partner&HDMS2=damoa&HDMS3=damoa&HDMS4=pet_pc&inpath=e_sure_market', '보장(담보) 총14종'),
    ('41635960-6d79-4dba-aaca-6dc97dc342e5', 'DOG', 'KB손보', 'KB 다이렉트 금쪽같은 펫보험(강아지)(무배당)(26.07)', 42903.00, 'MONTHLY', 0, 80, 'OWNER', '온라인가입', 'https://direct.kbinsure.co.kr/home/#/GL/LPD/LT_CM0101M/', '보장(담보) 총 7종<br/>20년만기/20년납/펫 가입(1~3년갱신)<br/>순수보장형'),
    ('39914c67-5921-4328-8d93-7fb7b2265ffb', 'DOG', '현대해상', '(무)현대해상다이렉트굿앤굿우리펫보험(재가입용)(Hi2605) 1종<반려견>_표준플랜', 44410.00, 'MONTHLY', 1, 19, 'OWNER', '온라인가입', 'https://mdirect.hi.co.kr/service.do?m=5e5408d724&utm_source=damoa&utm_medium=partner&utm_campaign=pet_renew_mo&HDMS1=partner&HDMS2=damoa&HDMS3=damoa&HDMS4=pet_renew_mo&inpath=e_sure_market', '보장(담보) 총9종'),
    ('837585c0-4c53-4bb0-a89d-fa32749036f6', 'DOG', 'KB손보', 'KB 다이렉트 금쪽같은 펫보험(강아지)(재가입용)(무배당)(26.05)', 48914.00, 'MONTHLY', 1, 19, 'PET', '온라인가입', 'ㅡ', '보장(담보) 총 2종<br/>1년만기/전기납/월납/반려동물 1세<br/>연령은 반려동물의 연령을 의미<br/>순수보장형'),
    ('db9bc307-b9c1-4c06-848b-5f5360d585bc', 'DOG', '롯데손보', '댕댕이랑 여행갈땐보험', 502.50, 'ANNUAL', 0, 20, 'PET', '온라인가입', 'https://alice.lotteins.co.kr', '보험기간 1년'),
    ('6982bc55-6e54-4518-93b7-94b2b0d6dede', 'DOG', '마이브라운', '강아지만 생각하는 강아지보험', 27595.00, 'MONTHLY', 0, 10, 'PET', '온라인가입', 'www.mybrown.co.kr', '월납보험료'),
    ('10005195-9f43-442b-9e44-889a4a9e2afd', 'CAT', '삼성화재', '무배당 삼성화재 다이렉트 반려묘보험(2601.7)(자동갱신형)', 21541.00, 'MONTHLY', 0, 80, 'OWNER', '온라인가입', 'http://direct.samsungfire.com/CR_MyAnycarWeb/overture_index.jsp?OTK=PE2304OB100', '보장(담보) 총6종 <br/> 3,5년만기/전기납 <br/> 남자, 40세, 상해1급 <br/> 코리안숏헤어 0세 가입'),
    ('5f466031-5796-456f-8cdc-b3a982ab5944', 'CAT', '카카오페이손해보험', '무배당 펫보험', 28322.00, 'MONTHLY', 0, 3, 'PET', '모바일가입', 'https://kakaopayinscorp.co.kr/products/PET?dc=DMA100&utm_source=partner_damoa&utm_medium=b2c_damoa&utm_campaign=damoa_pet&utm_content=pet', '40세 남자, 20년만기 전기납, 반려묘 코리안숏헤어 0세, 갱신형 특별약관 3년만기 전기납, 재가입형 특별약관 1년만기 전기납, 월납'),
    ('bc669c6f-a5f5-4778-a85a-300aa23daf26', 'CAT', '현대해상', '(무)현대해상다이렉트굿앤굿우리펫보험(재가입용)(Hi2605) 2종<반려묘>_표준플랜', 28650.00, 'MONTHLY', 1, 19, 'OWNER', '온라인가입', 'https://mdirect.hi.co.kr/service.do?m=5e5408d724&utm_source=damoa&utm_medium=partner&utm_campaign=cat_renew_mo&HDMS1=partner&HDMS2=damoa&HDMS3=damoa&HDMS4=cat_renew_mo&inpath=e_sure_market', '보장(담보) 총5종'),
    ('3b0392a4-47c7-4df6-b251-c77d65e5d36c', 'CAT', '농협손보', '(무) NH다이렉트펫앤미든든보험[2종:고양이]2604', 29336.00, 'MONTHLY', 0, 80, 'OWNER', '모바일가입', 'https://www.nhfire.co.kr/product/retrieveProduct.nhfire?pdtCd=D314522', '보장(담보) 총 5종, 20년만기/20년납, 남자 40세 기준'),
    ('341d17e8-eb57-4655-871e-6e909fac7b12', 'CAT', 'KB손보', 'KB 다이렉트 금쪽같은 펫보험(고양이)(재가입용)(무배당)(26.05)', 31815.00, 'MONTHLY', 1, 19, 'PET', '온라인가입', 'ㅡ', '보장(담보) 총 2종<br/>1년만기/전기납/월납/반려동물 1세<br/>연령은 반려동물의 연령을 의미<br/>순수보장형'),
    ('c02de174-a35e-4917-991f-8a4efaf3ef57', 'CAT', '메리츠화재', '(무) 펫퍼민트 Cat&Family보험 다이렉트2604', 32432.00, 'MONTHLY', 18, 80, 'OWNER', '온라인가입', 'https://store.meritzfire.com/pet/product-cat.do#!/', '보장(담보) 총 7종 <br/> 남자 40세, 상해1급, 20년만기/전기납 <br/> 반려묘 70%보장형 자기부담금 3만원, 1년만기/전기납 <br/> 순수보장형'),
    ('b0044e23-9add-4c72-9033-8b96a65a969b', 'CAT', 'DB손보', '(무)다이렉트 펫블리 반려묘 의료비보험2605(CM)(재가입용)', 32522.00, 'MONTHLY', 1, 19, 'PET', '온라인가입', 'https://www.directdb.co.kr/ltm/prd/cpnmcat/custInfoView.do?searchPdcCd=31067&searchPdcTrtHistCd=00&pdcDvcd=l_pet_cat', '보장(담보) 총5종 <br/> 코리안숏헤어 1세 가입 (1년만기재가입 : 반려동물의료비) <br/> 월납 기준'),
    ('cb563647-d69a-4553-942c-022d90489d2c', 'CAT', '현대해상', '(무)현대해상다이렉트굿앤굿우리펫보험(Hi2601) 2종<반려묘>_스탠다드플랜', 32910.00, 'MONTHLY', 19, 80, 'OWNER', '온라인가입', 'https://direct.hi.co.kr/service.do?m=108256981a&petType=C&utm_source=damoa&utm_medium=partner&utm_campaign=cat_pc&HDMS1=partner&HDMS2=damoa&HDMS3=damoa&HDMS4=cat_pc&inpath=e_sure_market', '보장(담보) 총9종'),
    ('9829f81b-01e3-4d2c-aae8-4f36fd151154', 'CAT', 'DB손보', '(무)다이렉트 펫블리 반려묘보험2607(CM)', 34692.00, 'MONTHLY', 0, 80, 'OWNER', '온라인가입', 'https://www.directdb.co.kr/ltm/prd/cpnmcat/custInfoView.do?searchPdcCd=31067&searchPdcTrtHistCd=00&pdcDvcd=l_pet_cat', '보장(담보) 총5종 <br/> 20년만기/20년납 <br/> 코리안숏헤어 0세 가입(5년 갱신 : 장례지원비담보 / 1년만기재가입 : 반려동물의료비)'),
    ('ad26f25e-767c-44d1-ad5a-f8df2ee10941', 'CAT', 'KB손보', 'KB 다이렉트 금쪽같은 펫보험(고양이)(무배당)(26.07)', 35158.00, 'MONTHLY', 0, 80, 'OWNER', '온라인가입', 'https://direct.kbinsure.co.kr/home/#/GL/LPC/LT_CM0101M/', '보장(담보) 총 6종<br/>20년만기/20년납/펫 가입(1~3년갱신)<br/>순수보장형'),
    ('17bef179-e052-4df9-9908-d7689a3ecb24', 'CAT', '마이브라운', '고양이만 생각하는 고양이보험', 30843.00, 'MONTHLY', 0, 10, 'PET', '온라인가입', 'www.mybrown.co.kr', '월납보험료');

INSERT INTO `insurance_product_coverage`
    (`coverage_id`, `product_id`, `coverage_name`, `coverage_amount`)
VALUES
    ('f10b2def-4368-49b9-b2b3-d25124cf9fab', 'ba916088-b4ff-4e69-8b02-68df9980ed12', '상해 후유장해(80%이상)', '1000만원'),
    ('df7f39d6-444e-4721-ae12-76c138ee87c6', 'a9c71c30-a2d7-42ce-85c7-c61061a4a3fd', '반려동물의료비Ⅵ(슬관절/고관절탈구포함(1년이후))(실손)', '1000만원'),
    ('2190e05d-9dfa-4772-a84a-b32e6cb210c5', 'a9c71c30-a2d7-42ce-85c7-c61061a4a3fd', '반려동물의료비Ⅵ 치과및구강질환 확장보장(실손)', NULL),
    ('f28c431c-9a6b-4985-b645-9068d55465cd', 'a9c71c30-a2d7-42ce-85c7-c61061a4a3fd', '반려동물의료비Ⅵ 특정피부약물치료 확장보장(실손)', NULL),
    ('cc1aaef7-d4e0-497a-8505-c2a4295c6240', 'a9c71c30-a2d7-42ce-85c7-c61061a4a3fd', '반려동물 장례지원비(개)(갱신형)', '30만원'),
    ('e8c46795-16b7-4e5f-827f-08a8404975e6', '25638eb5-ed78-48cf-8610-1508d30f061f', '반려동물 치료비Ⅲ(강아지)', '1000만원'),
    ('35d44017-23b7-4d3d-9f68-3c21d66ff06a', '25638eb5-ed78-48cf-8610-1508d30f061f', '반려동물 치료비Ⅲ 구강질환 확장보장(강아지)', NULL),
    ('f53bfe0a-d0fe-4751-86be-3e89c2ed3ee8', '25638eb5-ed78-48cf-8610-1508d30f061f', '반려동물 치료비Ⅲ 피부질환 확장보장(강아지)', NULL),
    ('3254df44-c093-4cc1-8a27-579a8f6e1a5f', '25638eb5-ed78-48cf-8610-1508d30f061f', '(갱신형)반려동물 배상책임(강아지)', '500만원'),
    ('c5d383bf-41b5-4085-80ae-e41737c520eb', '25638eb5-ed78-48cf-8610-1508d30f061f', '(갱신형)반려동물 사망(강아지)', '15만원'),
    ('b98d97e0-f9f5-4eca-8536-5dfeafea389b', '6f6eaea1-c843-474a-bfe8-5636dde07ace', '반려동물 치료비Ⅲ(강아지)', '1000만원'),
    ('1f10b5cc-60eb-4508-b2b7-7241bcc90dd2', '6f6eaea1-c843-474a-bfe8-5636dde07ace', '반려동물 치료비Ⅲ 구강질환 확장보장(강아지)', NULL),
    ('d7a5f280-8e98-44e8-979f-20d3302fb9a0', '6f6eaea1-c843-474a-bfe8-5636dde07ace', '반려동물 치료비Ⅲ 피부질환 확장보장(강아지)', NULL),
    ('159b73e1-eb3d-4b71-a246-0aa9f8233706', '6f6eaea1-c843-474a-bfe8-5636dde07ace', '(갱신형)반려동물 배상책임(강아지)', '500만원'),
    ('6eaf2532-8bbb-461a-bc48-d5ba3599d5c7', '6f6eaea1-c843-474a-bfe8-5636dde07ace', '(갱신형)반려동물 사망(강아지)', '15만원'),
    ('41f2f450-eb69-4a93-a144-aa1cd6f8d8aa', '2e3de3a3-1cbb-4ce3-970f-bb37fcb0961c', '펫퍼민트 반려견 통원의료비보장', '500만원 한도'),
    ('8d48f30a-6732-4205-8a1b-4b287c7158be', '2e3de3a3-1cbb-4ce3-970f-bb37fcb0961c', '펫퍼민트 반려견 입원의료비보장', NULL),
    ('12eebc65-d494-4814-a406-cd7045383012', '2e3de3a3-1cbb-4ce3-970f-bb37fcb0961c', '갱신형 펫퍼민트 반려견 배상책임보장', '1000만원 한도'),
    ('e64206c6-0e8a-4a53-8e2d-b16c8992a2bc', 'fc4d9bae-4968-491f-98eb-da7d15c20f06', '반려동물의료비Ⅵ(슬관절/고관절탈구포함(1년이후))(실손)', '1000만원'),
    ('0ee56039-e3e4-45a7-aa56-1615cab75d77', 'fc4d9bae-4968-491f-98eb-da7d15c20f06', '반려동물의료비Ⅵ 치과및구강질환 확장보장(실손)', NULL),
    ('07d546de-aec4-4347-a731-6f3ac5fbde9f', 'fc4d9bae-4968-491f-98eb-da7d15c20f06', '반려동물의료비Ⅵ 특정피부약물치료 확장보장(실손)', NULL),
    ('fbbc538d-b38a-40f9-8b5d-675af83eafb8', 'fc4d9bae-4968-491f-98eb-da7d15c20f06', '반려동물의료비Ⅵ(MRI,CT,내시경 검사시 보장금액확대)(연간1회한)(실손)(50%보상)', '100만원'),
    ('ba473894-679a-4c72-be46-c86c031c7181', 'd1027ae7-9862-426a-87ea-c776cfb13b14', '반려견 의료비(수술, 입원, 통원)', '4000만원'),
    ('e9fa1761-f71c-4a1f-9bb1-331e3f4e5ee8', 'd1027ae7-9862-426a-87ea-c776cfb13b14', '반려견 배상책임보장(갱신형)', '500만원'),
    ('f604f7ae-cbe6-46f7-8790-e9a1fd064f8d', 'd1027ae7-9862-426a-87ea-c776cfb13b14', '반려견 무지개다리위로금(갱신형)', '30만원'),
    ('1bdc140a-47d4-444c-9ac7-a9ff3dae8c5a', 'a8de7471-0a40-4b89-997c-5e004f9aeb65', '반려동물돌봄비(상해입원일당(1-10일))', '1만원'),
    ('b548622c-e5f5-4d2d-9ac5-0307637e2c56', 'a8de7471-0a40-4b89-997c-5e004f9aeb65', '응급실내원진료비(응급)', '10만원'),
    ('fe70a610-ef2a-480c-80e6-0c16611958be', 'a8de7471-0a40-4b89-997c-5e004f9aeb65', '반려견입원의료비Ⅱ', '15백만원'),
    ('f235e6f1-09d2-4a23-80cd-984b2e634aae', 'a8de7471-0a40-4b89-997c-5e004f9aeb65', '반려견통원의료비Ⅱ', NULL),
    ('e5c0e05c-a47f-4826-8fed-e6f96716804f', 'a8de7471-0a40-4b89-997c-5e004f9aeb65', '반려견입원의료비확장보장(치과및구강질환)Ⅱ', NULL),
    ('b560b732-312b-404c-a333-8de0ed1001ae', 'a8de7471-0a40-4b89-997c-5e004f9aeb65', '반려견통원의료비확장보장(치과및구강질환)Ⅱ', NULL),
    ('77651ee2-cb31-48d0-ac9d-132eb03a8e5e', 'a8de7471-0a40-4b89-997c-5e004f9aeb65', '반려견입원의료비확장보장(슬관절,고관절 탈구)', NULL),
    ('ed11644a-84ce-402e-81fa-8c31cbcae5dd', 'a8de7471-0a40-4b89-997c-5e004f9aeb65', '반려견통원의료비확장보장(슬관절,고관절 탈구)', NULL),
    ('d27d6849-fe78-4785-9fb2-f7d882191da0', 'a8de7471-0a40-4b89-997c-5e004f9aeb65', '반려견의료비확장보장(MRI/CT)(연간1회한)', '100만원'),
    ('e20fdb62-7cf6-4c22-b765-1daff61427e1', 'a8de7471-0a40-4b89-997c-5e004f9aeb65', '반려견의료비확장보장(특정처치(이물제거)(연간2회한)', '200만원'),
    ('5174c09b-01f6-4591-91d1-fbc836afeb97', 'a8de7471-0a40-4b89-997c-5e004f9aeb65', '반려견의료비확장보장(특정약물치료)(연간12회한)담보', '10만원'),
    ('196d3c78-b434-4e21-8cf6-61d3049d9905', 'a8de7471-0a40-4b89-997c-5e004f9aeb65', '반려견사망위로금(갱신형)', '30만원'),
    ('d04502d0-3a76-4eae-97b5-a78ee2116cb8', 'a8de7471-0a40-4b89-997c-5e004f9aeb65', '반려견배상책임Ⅱ(갱신형)', '1천만원'),
    ('1b5e6a8f-0941-44d0-b2cb-0ac31de56cf4', '41635960-6d79-4dba-aaca-6dc97dc342e5', '반려동물 위탁비용(반려인 상해입원 1일이상 180일한도)(실손)(강아지)', '5만원'),
    ('6186dfbe-96aa-401c-aa51-a410b157a252', '41635960-6d79-4dba-aaca-6dc97dc342e5', '반려동물의료비Ⅱ(강아지)', '2000만원'),
    ('2307c9f5-b62b-4568-a424-0d53133ed9af', '41635960-6d79-4dba-aaca-6dc97dc342e5', '반려동물의료비확장보장Ⅱ(주요치료)(강아지)', '1000만원'),
    ('0469fe07-a9ed-48b3-87cc-f72dd01c2f8b', '41635960-6d79-4dba-aaca-6dc97dc342e5', '반려동물배상책임(강아지)(갱신형)', NULL),
    ('e885f0bd-8ed3-436b-8294-2dcec54921dd', '41635960-6d79-4dba-aaca-6dc97dc342e5', '반려동물장례비용지원금(실손)(30일면책)(강아지)(갱신형)', '100만원'),
    ('b24b7660-52da-405a-91a6-935c3e048cc9', '41635960-6d79-4dba-aaca-6dc97dc342e5', '무지개다리위로금(강아지, 사망)(갱신형)', '30만원'),
    ('3772d407-27fe-4dd8-b119-c163513b2dee', '39914c67-5921-4328-8d93-7fb7b2265ffb', '기본계약(반려견통원의료비Ⅱ(재가입용))', NULL),
    ('f2afdd74-e5f5-43b9-86c3-d0ebfa05a359', '39914c67-5921-4328-8d93-7fb7b2265ffb', '반려견입원의료비확장보장(슬관절,고관절 탈구)(재가입용)', NULL),
    ('1c086d2c-db87-4d0b-9fd7-08b1eb6c0f8b', '39914c67-5921-4328-8d93-7fb7b2265ffb', '반려견통원의료비확장보장(슬관절,고관절 탈구)(재가입용)', NULL),
    ('11b54d33-e49f-42b6-94dc-5a292cd34292', '39914c67-5921-4328-8d93-7fb7b2265ffb', '반려견의료비확장보장(MRI/CT)(연간1회한)(재가입용)', '100만원'),
    ('b8d61d87-d21a-452c-a8c6-824df5e5bdec', '39914c67-5921-4328-8d93-7fb7b2265ffb', '반려견의료비확장보장(특정처치(이물제거))(연간2회한)(재가입용)', '200만원'),
    ('fa0fcdc0-ae3e-4095-a0a6-d6f592fa0308', '39914c67-5921-4328-8d93-7fb7b2265ffb', '반려견입원의료비확장보장(치과및구강질환)Ⅱ(재가입용)', '15백만원'),
    ('6f6eec2b-701a-4b8b-aaea-ef4ee8afcf14', '39914c67-5921-4328-8d93-7fb7b2265ffb', '반려견통원의료비확장보장(치과및구강질환)Ⅱ(재가입용)', NULL),
    ('9b85488d-1e11-452c-951b-0b815310cf6d', '39914c67-5921-4328-8d93-7fb7b2265ffb', '반려견의료비확장보장(특정약물치료)(연간12회한)(재가입용)', '10만원'),
    ('0e8b8eee-f5b4-4317-aa43-c3c22213f62b', '837585c0-4c53-4bb0-a89d-fa32749036f6', '반려동물의료비확장보장Ⅱ(주요치료)(강아지)', '1000만원'),
    ('98b63e8d-492b-48d1-8522-9dc36b560d21', 'db9bc307-b9c1-4c06-848b-5f5360d585bc', '반려동물 위탁비용(반려인 상해입원 1일이상 180일 한도)', '5만원'),
    ('8578b4ce-5dbf-4c77-80d2-f80906e85627', 'db9bc307-b9c1-4c06-848b-5f5360d585bc', '반려동물 양육자금(일반상해사망)', '500만원'),
    ('b344c5fd-de78-4832-8729-c0ce08834fc9', '6982bc55-6e54-4518-93b7-94b2b0d6dede', '수술 및 특정고액치료비', '200만원'),
    ('08ebc9d1-786e-4a62-909e-d16321d757ed', '6982bc55-6e54-4518-93b7-94b2b0d6dede', '반려동물 배상책임', '3000만원'),
    ('346693d2-c049-48d5-b5e5-d1ebb580f7e0', '6982bc55-6e54-4518-93b7-94b2b0d6dede', '무지개별 여행비', '30만원'),
    ('08562a6b-6a2b-4f95-ad62-3190fe7e415d', '10005195-9f43-442b-9e44-889a4a9e2afd', '반려묘의료비(치과및구강질환포함)(50%보상형)(재가입형)', '1일당 10만원/연간 1000만원'),
    ('fe260b99-a157-4a48-ac3f-ba7fbad190e2', '10005195-9f43-442b-9e44-889a4a9e2afd', '반려묘 수술비(치과및구강질환포함) 확대보장(50%보상형)(재가입형)', '200만원'),
    ('32969fda-b254-457c-8272-6c117f4ca25a', '10005195-9f43-442b-9e44-889a4a9e2afd', '반려묘 의료비 확대보장(이물제거 특정처치)(연간2회한)(50%보상형)(재가입형)', NULL),
    ('dc3f0690-09df-4c0d-9b31-795226f0e9ef', '10005195-9f43-442b-9e44-889a4a9e2afd', '반려묘의료비확대보장(MRI,CT)(연간1회한)(50%보상형)(재가입형)', '100만원'),
    ('f38415b7-f04d-4810-9155-2fb1e0936eeb', '10005195-9f43-442b-9e44-889a4a9e2afd', '[갱신]반려묘사망위로금', '30만원'),
    ('df637d8f-7768-492b-b299-9a39940a7616', '5f466031-5796-456f-8cdc-b3a982ab5944', '반려묘 의료비(수술, 입원, 통원)', '4000만원'),
    ('5a8a5cd6-a9ca-4ba2-bddd-ea93aefda40d', '5f466031-5796-456f-8cdc-b3a982ab5944', '반려묘 무지개다리위로금(갱신형)', '30만원'),
    ('18bfee39-b52a-488f-ab59-da74c44e90a1', 'bc669c6f-a5f5-4778-a85a-300aa23daf26', '기본계약(반려묘통원의료비Ⅱ(재가입용))', NULL),
    ('1566dda3-0fc7-485e-ba6b-be09575ea8ee', 'bc669c6f-a5f5-4778-a85a-300aa23daf26', '반려묘의료비확장보장(MRI/CT)(연간1회한)(재가입용)', '100만원'),
    ('0aab4f6c-7425-49f4-9014-9e7c58001439', 'bc669c6f-a5f5-4778-a85a-300aa23daf26', '반려묘입원의료비확장보장(치과및구강질환)Ⅱ(재가입용)', '15백만원'),
    ('d6ae830c-2f7c-4cd1-9267-cf4398b43477', 'bc669c6f-a5f5-4778-a85a-300aa23daf26', '반려묘통원의료비확장보장(치과및구강질환)Ⅱ(재가입용)', NULL),
    ('1b5fda85-c625-4133-9feb-d65312668316', '3b0392a4-47c7-4df6-b251-c77d65e5d36c', '반려동물 치료비Ⅲ(고양이)', '1000만원'),
    ('76913f26-2f04-4645-b5a0-73cc443718cd', '3b0392a4-47c7-4df6-b251-c77d65e5d36c', '반려동물 치료비Ⅲ 구강질환 확장보장(고양이)', NULL),
    ('0939df94-26dd-452e-a407-b3eb90375de5', '3b0392a4-47c7-4df6-b251-c77d65e5d36c', '반려동물 치료비Ⅲ 비뇨기질환 확장보장(고양이)', NULL),
    ('5e8d7313-d383-48b6-bb95-d892a91b0ac5', '3b0392a4-47c7-4df6-b251-c77d65e5d36c', '(갱신형)반려동물 사망(고양이)', '15만원'),
    ('0fe1a3e7-6a75-4fc6-8325-ec3fb9d07be2', '341d17e8-eb57-4655-871e-6e909fac7b12', '반려동물의료비확장보장Ⅱ(주요치료)(고양이)', '1000만원'),
    ('045f0d6a-e29e-499d-8400-7c009ccc5634', 'c02de174-a35e-4917-991f-8a4efaf3ef57', '펫퍼민트 반려묘 통원의료비보장', '500만원 한도'),
    ('032df0ff-e43e-42f2-9741-533bd9e0e690', 'c02de174-a35e-4917-991f-8a4efaf3ef57', '펫퍼민트 반려묘 입원의료비보장', NULL),
    ('2b878b5b-1149-4a0e-a87c-ebe4a9e885bf', 'b0044e23-9add-4c72-9033-8b96a65a969b', '반려동물의료비Ⅵ 치과및구강질환 확장보장Ⅱ(고양이)(실손)', NULL),
    ('127667ad-6449-4b8b-ae0a-e35dfbdb7021', 'b0044e23-9add-4c72-9033-8b96a65a969b', '반려동물의료비Ⅵ(고양이)(MRI,CT,내시경 검사시 보장금액확대)(연간1회한)', '보험가입금액(100만원)'),
    ('99c792fa-f10b-4697-9b53-a7b297644575', 'cb563647-d69a-4553-942c-022d90489d2c', '반려동물돌봄비(상해입원일당(1-10일))', '1만원'),
    ('b7f9263d-edb9-4227-a8cf-4b89f5c7b36c', 'cb563647-d69a-4553-942c-022d90489d2c', '응급실내원진료비(응급)', '10만원'),
    ('2622b532-f985-4163-b5c3-b8bb287a2da1', 'cb563647-d69a-4553-942c-022d90489d2c', '반려묘입원의료비Ⅱ', '15백만원'),
    ('81671980-ac3a-4d26-95ab-f946dc907ca1', 'cb563647-d69a-4553-942c-022d90489d2c', '반려묘통원의료비Ⅱ', NULL),
    ('aa79c0eb-2ac8-4767-95a4-c19ace126668', 'cb563647-d69a-4553-942c-022d90489d2c', '반려묘입원의료비확장보장(치과및구강질환)Ⅱ담보', NULL),
    ('5ed0277b-48b7-4b48-888a-77f062e64d3c', 'cb563647-d69a-4553-942c-022d90489d2c', '반려묘통원의료비확장보장(치과및구강질환)Ⅱ담보', NULL),
    ('d9874009-b6aa-4da4-9717-39a00cd26e50', 'cb563647-d69a-4553-942c-022d90489d2c', '반려묘의료비확장보장(MRI/CT)(연간1회한)', '100만원'),
    ('8342a9ac-0ce4-4dce-ad31-126c1f1b13f1', 'cb563647-d69a-4553-942c-022d90489d2c', '반려묘사망위로금(갱신형)', '30만원'),
    ('322452dd-badb-43ef-9276-8cd318ffbc20', '9829f81b-01e3-4d2c-aae8-4f36fd151154', '반려동물의료비Ⅵ(고양이)(실손)', '보험가입금액(1000만원)'),
    ('f5ac6c88-ae6e-431e-a67d-212736275222', '9829f81b-01e3-4d2c-aae8-4f36fd151154', '반려동물의료비Ⅵ 치과및구강질환 확장보장Ⅱ(고양이)(실손)', NULL),
    ('51e0ae1f-c70d-4c21-abc2-f985f52c5235', '9829f81b-01e3-4d2c-aae8-4f36fd151154', '반려동물의료비Ⅵ(고양이)(MRI,CT,내시경 검사시 보장금액확대)(연간1회한)', '보험가입금액(100만원)'),
    ('f4f27ba8-5658-4aee-b482-dac935ca5a5c', '9829f81b-01e3-4d2c-aae8-4f36fd151154', '반려동물 장례지원비(고양이)(갱신형)', '보험가입금액(30만원)'),
    ('621eed0c-7c6f-4386-9049-e3b619fde17d', 'ad26f25e-767c-44d1-ad5a-f8df2ee10941', '반려동물 위탁비용(반려인 상해입원 1일이상 180일한도)(실손)(고양이)(갱신형)', '5만원'),
    ('2431604c-4aa1-4636-bc4f-6644e46f8195', 'ad26f25e-767c-44d1-ad5a-f8df2ee10941', '반려동물의료비Ⅱ(고양이)', '2000만원'),
    ('5b25bb52-f16d-40d2-8e15-f3e90da9f0ff', 'ad26f25e-767c-44d1-ad5a-f8df2ee10941', '반려동물의료비확장보장Ⅱ(주요치료)(고양이)', '1000만원'),
    ('34add7f4-64c5-48cb-987c-20ab5d4e94b8', 'ad26f25e-767c-44d1-ad5a-f8df2ee10941', '무지개다리위로금(고양이, 사망)(갱신형)', '30만원'),
    ('34efa7c8-c3f8-4305-813d-a41f739b08fc', 'ad26f25e-767c-44d1-ad5a-f8df2ee10941', '반려동물장례비용지원금(실손)(30일면책)(고양이)(갱신형)', '70만원'),
    ('7c1550f1-57a4-4f42-a0ee-7dda03da1b2d', '17bef179-e052-4df9-9908-d7689a3ecb24', '수술 및 특정고액치료비', '200만원'),
    ('cd003edd-18cf-4a15-adc1-ce7a2247bd97', '17bef179-e052-4df9-9908-d7689a3ecb24', '무지개별 여행비', '30만원');

-- ---------------------------------------------------------------------
-- insurance_simulation: V1 원본 컬럼을 새 응답 구조(고정 가정치 기반
-- assumptions + 상품별 breakEvenScenarios) 기준으로 재정의한다.
-- breed/age는 petId로 pet 테이블을 조회해서 얻으므로 중복 저장하지 않는다.
-- estimated_annual_cost/premium/deductible/break_even_year는 단일값이
-- 아니라 상품별 시나리오 배열로 대체되어 더 이상 컬럼으로 저장하지 않는다.
-- ---------------------------------------------------------------------
ALTER TABLE `insurance_simulation`
    CHANGE COLUMN `medical_history` `medical_history_codes` JSON NULL,
    CHANGE COLUMN `recommendation` `verdict` VARCHAR(20) NULL COMMENT 'FAVORABLE / NEUTRAL / UNFAVORABLE',
    ADD COLUMN `message` TEXT NULL AFTER `verdict`,
    DROP COLUMN `breed`,
    DROP COLUMN `age`,
    DROP COLUMN `estimated_annual_cost`,
    DROP COLUMN `premium`,
    DROP COLUMN `deductible`,
    DROP COLUMN `break_even_year`;
