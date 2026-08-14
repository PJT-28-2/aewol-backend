-- =====================================================================
-- V28: 보험 상품 카테고리/자기부담금-계산순서 컬럼 추가 + 환급률/자기부담금/
--      연간한도/출처/신뢰도의 진실 원천을 insurance_product_plan_tiers로 이전
--
-- 계획 문서: .omc/plans/pet-insurance-simulator-fix-consensus.md (v3.1) S4
-- worker-4 재설계 사유: 국내 펫보험의 보장비율(환급률)·자기부담금은 상품의 고정
-- 속성이 아니라 가입 시 선택하는 "플랜" 옵션이다(농협손보·현대해상은 50/70/80/90%
-- 환급률 + 1만/3만/5만원 자기부담금 중 선택하는 구조임이 리서치로 확인됨,
-- docs/insurance-reimbursement-research.md 파일럿 로그 참조). V2가 이미 이 사실을
-- 반영해 insurance_product_plan_tiers(tier_id/product_id/tier_name/
-- reimbursement_rate_pct/is_reference_tier/premium_krw)를 만들어 두었으나 0행이었다.
-- "이 견적이 어떤 플랜을 전제로 뽑혔는가"가 진실이지 "이 상품의 환급률은 X%다"는
-- 거짓 주장이므로, is_reference_tier=1 티어를 진실 원천으로 승격한다.
--
-- ⚠️ 파일명 순연 안내: 계획서상 원래 파일명은 V27이었으나, 이 저장소에 이미
-- V27__create_wallet_withdrawal_request.sql(지갑 출금 기능, 본 작업과 무관)이
-- 존재한다. Flyway는 버전 번호 유일성을 강제하므로 같은 번호를 재사용할 수
-- 없어, 계획서의 V27을 V28로, V28을 V29로 순연했다. ALTER 문/컬럼/내용은
-- 계획서와 동일하고, 버전 숫자만 다르다 (자세한 사유는 최초 작성 당시 주석 참조,
-- 아래 재설계로 일부 컬럼 위치만 달라졌다).
--
-- 매칭 키 안내 (하단 TODO 블록의 백필 작성 시 반드시 준수):
--   백필은 species + company_name + product_name 3개를 모두 WHERE 조건으로
--   사용해야 한다. 카카오페이손해보험 '무배당 펫보험'이 DOG(V3:194)·CAT(V3:202)
--   양쪽에 동일한 company_name/product_name으로 존재하고, insurance_product에는
--   (species, company_name, product_name) UNIQUE 제약이 없으므로(V2:17-39),
--   company_name + product_name 2개 키만 쓰면 강아지 값이 고양이 상품(또는 그
--   반대)을 덮어쓰는 교차 오염이 발생한다 (계획서 R3).
-- =====================================================================

-- 롤백 대비 백업. Flyway Community는 undo를 제공하지 않고, 아래 백필은 전부
-- UPDATE/INSERT 기반이라 원본 값을 별도로 보존해두지 않으면 발표 직전 오류 발견
-- 시 되돌릴 수단이 없다 (계획서 R12). ALTER(컬럼 DROP 포함) 직전에 떠서
-- reimbursement_rate_pct/reimbursement_confidence(아래에서 DROP됨)를 포함한
-- 현재 스키마 전체를 스냅샷으로 남긴다.
--
-- 복구 명령 (컬럼 단위 역-UPDATE — CREATE TABLE ... AS SELECT는 인덱스/제약을
-- 복사하지 않으므로 테이블 스왑이 아니라 컬럼 단위로 되돌려야 한다):
--   -- 1) 이 마이그레이션이 DROP한 컬럼을 재생성
--   ALTER TABLE insurance_product
--     ADD COLUMN reimbursement_rate_pct INT NULL,
--     ADD COLUMN reimbursement_confidence VARCHAR(30) NULL;
--   -- 2) 백업에서 값 복원
--   UPDATE insurance_product p JOIN insurance_product_bak_v27 b ON p.product_id = b.product_id
--   SET p.reimbursement_rate_pct = b.reimbursement_rate_pct,
--       p.reimbursement_confidence = b.reimbursement_confidence,
--       p.age_subject_confidence = b.age_subject_confidence,
--       p.product_url = b.product_url;
--   -- 3) 이 마이그레이션이 plan_tiers에 새로 넣은 견적 기준 티어 제거
--   DELETE FROM insurance_product_plan_tiers WHERE is_reference_tier = 1;
-- S3'(수정 후 확정 기대값, Verification 9) 통과 후 이 백업 테이블은 DROP한다.
CREATE TABLE insurance_product_bak_v27 AS SELECT * FROM insurance_product;

-- ---------------------------------------------------------------------
-- insurance_product: 상품 레벨에 남는 것 (플랜 선택과 무관한 상품 고유 속성)
--   product_category      : MEDICAL/LIABILITY_TRAVEL 분류 — 플랜과 무관
--   reimbursement_rate_note : 담보(coverage) 단위 환급률 차이 설명(Decision 1) —
--                             플랜(tier) 축이 아니라 "같은 플랜 안에서 담보마다
--                             다르다"는 별개의 축이라 상품 레벨에 남긴다
--   deductible_order      : 자기부담금 계산 순서(선차감/보장률 선적용) — 약관상
--                           보험사의 계산 방식 규칙이지 가입자가 선택하는 플랜
--                           옵션이 아니므로 상품 레벨에 남긴다
--
-- ⚠️ DROP: reimbursement_rate_pct, reimbursement_confidence (V2 원본 컬럼)
--   두 값 모두 "이 상품의 환급률/신뢰도"가 아니라 "이 견적이 전제한 플랜의
--   환급률/신뢰도"이므로 insurance_product_plan_tiers(아래)로 이전한다.
--   아직 백필이 하나도 안 들어간 시점이라 데이터 손실 없이 지금 DROP하는 것이
--   최적이다. 두 컬럼을 상품 레벨에 "비정규화 캐시"로 남기는 대안도 검토했으나
--   기각했다 — 캐시를 최신 상태로 유지하려면 티어 INSERT/UPDATE 때마다 상품
--   레벨도 같이 써야 해 두 번째 진실 원천이 생기고(Principle 4가 막으려는 바로
--   그 write-only/드리프트 위험), 서비스 계층도 어차피 tiersByProduct를 조회해야
--   해 캐시가 실제로 읽히지 않을 가능성이 높다. 하위 호환이 필요한 지점은
--   DTO 필드명(reimbursementRatePct 등)이지 SQL 컬럼명이 아니며, DTO 필드명은
--   그대로 유지하고 서비스 계층에서 티어 조회 결과로 채운다.
-- ---------------------------------------------------------------------
ALTER TABLE insurance_product
  DROP COLUMN reimbursement_rate_pct,
  DROP COLUMN reimbursement_confidence,
  ADD COLUMN product_category         VARCHAR(20)  NULL COMMENT 'MEDICAL / LIABILITY_TRAVEL',
  ADD COLUMN reimbursement_rate_note  VARCHAR(200) NULL COMMENT '담보별 환급률 차이 설명(화면 표시용)',
  ADD COLUMN deductible_order         VARCHAR(20)  NULL COMMENT 'BEFORE_RATE / AFTER_RATE (약관상 계산 순서, 플랜 무관)';

-- ---------------------------------------------------------------------
-- insurance_product_plan_tiers: 플랜(견적 기준) 레벨 진실 원천
--   reimbursement_rate_pct는 V2에서 이미 존재. 아래 5개를 신설한다.
--   deductible_krw / deductible_basis / annual_limit_krw / reimbursement_source_url /
--   reimbursement_confidence — 전부 "어떤 플랜을 선택했는가"에 종속되는 값이므로
--   상품이 아니라 티어에 속한다 (자기부담금이 플랜 선택 항목이라는 리서치 발견과 동일 이유).
-- ---------------------------------------------------------------------
ALTER TABLE insurance_product_plan_tiers
  ADD COLUMN deductible_krw           DECIMAL(15,2) NULL,
  ADD COLUMN deductible_basis         VARCHAR(20)  NULL COMMENT 'PER_VISIT / PER_YEAR',
  ADD COLUMN annual_limit_krw         DECIMAL(15,2) NULL COMMENT '표시 전용. 시드 담보 한도 대비 연 의료비가 낮아 산식에는 쓰이지 않음',
  ADD COLUMN reimbursement_source_url VARCHAR(500) NULL,
  ADD COLUMN reimbursement_confidence VARCHAR(30)  NULL COMMENT 'CONFIRMED_OWN_COVERAGE_NAME / ASSUMED_FROM_RESEARCH / UNVERIFIED',
  -- 감사 컬럼: V2 원본에는 없었다. insurance_product은 created_at을 갖고 있으나
  -- plan_tiers는 타임스탬프가 전무했다. "이 근거를 언제 기록했는가"는 심사 방어의
  -- 일부이고, 향후 CMS(관리자 화면)로 이 테이블을 편집하게 되면 필수가 된다.
  -- updated_by(member FK)는 아직 쓰는 주체가 없어 넣지 않는다 — CMS 도입 시 추가.
  ADD COLUMN created_at               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  ADD COLUMN updated_at               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;

-- ---------------------------------------------------------------------
-- S1 백필 안내 — ✅ 아래 BEGIN/END GENERATED 구간에 **생성 완료**
--
-- 이 블록은 백필의 "규칙"을 설명하는 참조 문서다. 실제 SQL은 아래 GENERATED
-- 구간에 있으며 scripts/gen-insurance-seed.mjs 가 리서치 문서로부터 생성한다.
-- 값을 여기서 손으로 고치지 말 것 — 문서를 고치고 재생성한다.
--
-- 현재 생성 결과: 견적 기준 티어 23건(24건 중 롯데 여행보험 제외),
--                 그중 CONFIRMED 5건 / UNVERIFIED 18건.
--
-- ⚠️ 범위 고정 (worker-4 지시서 명시): 이번 단계는 커버리지를 늘리는 작업이
-- 아니다. 확보된 근거는 여전히 docs/insurance-reimbursement-research.md 기준
-- 5건뿐이다(삼성화재 강아지 #1, 메리츠 강아지 #5, DB손보 강아지 재가입용 #6,
-- 메리츠 고양이 #19, 삼성화재 고양이 #14). 따라서 상품당 견적 기준 티어는
-- 최대 1개만 생기고, 화면에 손익분기가 뜨는 상품 수는 종전과 동일하다.
-- 값을 추측해서 티어를 늘리지 않는다.
--
-- AC-1 수정안(계획 문서 상단 참조): "24개 상품 전부에 대해 reimbursement_
-- confidence와 reimbursement_source_url이 기록되고..." — 이는 궁극적으로
-- 24개 상품 전부가 (확인했든 못했든) 정확히 1개의 is_reference_tier=1 행을
-- 가져야 함을 뜻한다. 못 찾은 19건도 reimbursement_rate_pct=NULL +
-- reimbursement_confidence='UNVERIFIED'인 티어 행을 INSERT해 "우리가 찾아봤고
-- 확인하지 못했다"는 사실 자체를 기록한다 (Principle 1 — 빈칸도 정직하게 기록).
-- 이 INSERT는 생성기가 이미 만들어 아래 GENERATED 구간에 들어가 있다.
-- 리서치 커버리지 확대(UNVERIFIED 18건을 줄이는 일)는 여전히 별도 작업이다.
--
-- 채울 컬럼 (species+company_name+product_name으로 상품을 먼저 특정한 뒤):
--
--   insurance_product_plan_tiers (product_id, tier_name, reimbursement_rate_pct,
--   is_reference_tier=1, premium_krw, deductible_krw, deductible_basis,
--   annual_limit_krw, reimbursement_source_url, reimbursement_confidence)
--     - tier_name: 리서치 문서의 플랜 표기를 그대로 사용 (예: '70%보장형')
--     - premium_krw: 견적에 쓰인 월납환산보험료(premium_monthly_equiv와 동일값,
--       스냅샷으로 별도 저장 — 상품 프리미엄이 나중에 바뀌어도 "그 시점 견적"이 보존됨)
--     - reimbursement_rate_pct: 확인 못하면 NULL
--     - reimbursement_confidence: CONFIRMED_OWN_COVERAGE_NAME / ASSUMED_FROM_RESEARCH / UNVERIFIED
--     - reimbursement_source_url: CONFIRMED_OWN_COVERAGE_NAME이면 NOT NULL 필수 (무결성 쿼리 1)
--     - deductible_krw / deductible_basis: 원본 확정 2건(메리츠 강아지·고양이,
--       자부담 3만원)부터 우선 반영 가능. 기준(건당/연간)은 원본에 없어 미확인 상태 유지
--     - annual_limit_krw: 표시 전용. 확보 안 되면 NULL 허용
--
--   insurance_product (species+company_name+product_name UPDATE):
--     - reimbursement_rate_note: 담보별 환급률이 갈리는 상품만. 예: DB손보 재가입용(#6) →
--       '항암 70% / MRI·CT 50% (담보별 상이, 대표값은 보수적 최저치)'
--     - deductible_order: 원본에 명시 안 됨 — 확인 전까지 NULL 유지
--     - age_subject_confidence: CONFIRMED / ASSUMED — age_basis 근거가 원본 비고/약관에서
--       확인되면 CONFIRMED, 확인 못하면 ASSUMED. 이 값은 어떤 값이든 상품 목록 화면에서
--       상품을 배제하지 않는다 — InsuranceProductPolicy.isEligibleByAge 교체로 보장됨 (Critic C-1)
--     - product_category: MEDICAL / LIABILITY_TRAVEL — 롯데손보 '댕댕이랑 여행갈땐보험'(#12)만
--       LIABILITY_TRAVEL 후보(담보가 배상책임·위탁비용·양육자금뿐, 의료비 0건).
--       나머지 23건은 개별 검토 후 MEDICAL. DEFAULT 없이 명시 UPDATE만 허용
--     - product_url: 무효 4건 정정 (KB손보 재가입용 강아지 #11·고양이 #18 'ㅡ',
--       마이브라운 강아지 #13·고양이 #24 스킴 없음 — AC-3)
--
-- 못 찾은 값은 추측으로 채우지 않고 NULL + reimbursement_confidence='UNVERIFIED'로
-- 남긴다 (Principle 1). '%' 자동 추출 금지 — 원본 문서의 '%' 대부분은 후유장해
-- 지급률(80%)이며 환급률이 아니다.
--
-- 작성 형식 예시 (5건 중 메리츠 강아지 #5 — 형식만 참고, 값은 아래 리서치
-- 문서 확정본을 그대로 옮길 것이며 이 파일에서 직접 확정값을 추측해 넣지 않는다.
-- 참고: docs/insurance-reimbursement-research.md 2절 "원본 확정 5건"):
--
-- INSERT INTO insurance_product_plan_tiers
--   (product_id, tier_name, reimbursement_rate_pct, is_reference_tier, premium_krw,
--    deductible_krw, deductible_basis, annual_limit_krw, reimbursement_source_url,
--    reimbursement_confidence)
-- SELECT product_id, '70%보장형', 70, 1, premium_monthly_equiv,
--        30000, NULL, NULL, NULL, 'CONFIRMED_OWN_COVERAGE_NAME'
-- FROM insurance_product
-- WHERE species = 'DOG' AND company_name = '메리츠화재'
--   AND product_name = '(무) 펫퍼민트 Puppy&Family보험 다이렉트2604';
-- -- 적용 직후 반드시 확인: 영향(삽입) 행 수가 정확히 1인가? (R3)
--
-- UPDATE insurance_product
-- SET product_category = 'MEDICAL', age_subject_confidence = ?
-- WHERE species = 'DOG' AND company_name = '메리츠화재'
--   AND product_name = '(무) 펫퍼민트 Puppy&Family보험 다이렉트2604';
-- ---------------------------------------------------------------------

-- =====================================================================
-- 아래 BEGIN/END GENERATED 구간은 자동 생성된다. 직접 편집하지 말 것.
--
--   생성기: aewol-backend/scripts/gen-insurance-seed.mjs
--   입력  : aewol-backend/docs/insurance-reimbursement-research.md (사람이 채우는 표)
--   실행  : cd aewol-backend && node scripts/gen-insurance-seed.mjs          (stdout 미리보기)
--           cd aewol-backend && node scripts/gen-insurance-seed.mjs --write  (이 구간 치환)
--
-- 새 근거를 찾으면 SQL을 손으로 고치지 말고 리서치 문서의 해당 행을 고친 뒤
-- --write로 재생성한다. 출처·confidence가 표에서 SQL 주석으로 그대로 따라온다.
--
-- ⚠️ Flyway 체크섬: 이 파일은 **DB에 적용되기 전까지만** 수정할 수 있다. 한 번
-- flywayMigrate가 돌면 체크섬이 기록되어 이후 파일이 바뀌면 검증에 실패한다.
-- 적용 후 새 근거가 나오면 이 구간을 고치지 말고 V30을 새로 만들 것.
-- =====================================================================
-- BEGIN GENERATED (S1 backfill)
-- 생성일: 2026-08-12 — 직접 편집하지 말 것 (scripts/gen-insurance-seed.mjs)
-- 입력: docs/insurance-reimbursement-research.md (24행 파싱)

-- ── #1 삼성화재 / 무배당 삼성화재 다이렉트 착한펫보험(강아지)(2601.6)(자동갱신형)
--    노트: 원본 엑셀 보장명에 (50%보상형) 명시
--    출처: 원본 엑셀 Desktop/펫보험/
INSERT INTO insurance_product_plan_tiers
  (product_id, tier_name, reimbursement_rate_pct, is_reference_tier, premium_krw,
   deductible_krw, deductible_basis, annual_limit_krw, reimbursement_source_url,
   reimbursement_confidence)
SELECT product_id, '50%보장형', 50, 1, premium_monthly_equiv,
       NULL, NULL, NULL, '원본 엑셀 Desktop/펫보험/',
       'CONFIRMED_OWN_COVERAGE_NAME'
FROM insurance_product
WHERE species = 'DOG'
  AND company_name = '삼성화재'
  AND product_name = '무배당 삼성화재 다이렉트 착한펫보험(강아지)(2601.6)(자동갱신형)';
UPDATE insurance_product
SET product_category = 'MEDICAL',
    age_subject_confidence = 'ASSUMED',
    product_url = 'http://direct.samsungfire.com/CR_MyAnycarWeb/overture_index.jsp?OTK=PE2210OB100'
WHERE species = 'DOG'
  AND company_name = '삼성화재'
  AND product_name = '무배당 삼성화재 다이렉트 착한펫보험(강아지)(2601.6)(자동갱신형)';

-- ── #2 DB손보 / (무)다이렉트 펫블리 반려견보험2607(CM)
--    노트: 미착수
INSERT INTO insurance_product_plan_tiers
  (product_id, tier_name, reimbursement_rate_pct, is_reference_tier, premium_krw,
   deductible_krw, deductible_basis, annual_limit_krw, reimbursement_source_url,
   reimbursement_confidence)
SELECT product_id, '견적 기준(플랜 미확인)', NULL, 1, premium_monthly_equiv,
       NULL, NULL, NULL, NULL,
       'UNVERIFIED'
FROM insurance_product
WHERE species = 'DOG'
  AND company_name = 'DB손보'
  AND product_name = '(무)다이렉트 펫블리 반려견보험2607(CM)';
UPDATE insurance_product
SET product_category = 'MEDICAL',
    age_subject_confidence = 'ASSUMED',
    product_url = 'https://www.directdb.co.kr/ltm/prd/cpnm/custInfoView.do?searchPdcCd=31054&searchPdcTrtHistCd=00&pdcDvcd=l_pet_dog'
WHERE species = 'DOG'
  AND company_name = 'DB손보'
  AND product_name = '(무)다이렉트 펫블리 반려견보험2607(CM)';

-- ── #3 농협손보 / (무) NH다이렉트펫앤미든든보험[1종:강아지]2604
--    노트: 파일럿 대상 — 아래 "파일럿 리서치 로그 #1" 참조. 보상비율 50%/70%·자부담 3만/5만원 "선택형" 정보 발견했으나 이 상품코드(D314512)의 실제 선택 조합 특정 불가로 미확정
INSERT INTO insurance_product_plan_tiers
  (product_id, tier_name, reimbursement_rate_pct, is_reference_tier, premium_krw,
   deductible_krw, deductible_basis, annual_limit_krw, reimbursement_source_url,
   reimbursement_confidence)
SELECT product_id, '견적 기준(플랜 미확인)', NULL, 1, premium_monthly_equiv,
       NULL, NULL, NULL, NULL,
       'UNVERIFIED'
FROM insurance_product
WHERE species = 'DOG'
  AND company_name = '농협손보'
  AND product_name = '(무) NH다이렉트펫앤미든든보험[1종:강아지]2604';
UPDATE insurance_product
SET product_category = 'MEDICAL',
    age_subject_confidence = 'ASSUMED',
    product_url = 'https://www.nhfire.co.kr/product/retrieveProduct.nhfire?pdtCd=D314512'
WHERE species = 'DOG'
  AND company_name = '농협손보'
  AND product_name = '(무) NH다이렉트펫앤미든든보험[1종:강아지]2604';

-- ── #4 농협손보 / (무) NH다이렉트펫앤미든든보험[3종:재가입용]2604
--    노트: 미착수
INSERT INTO insurance_product_plan_tiers
  (product_id, tier_name, reimbursement_rate_pct, is_reference_tier, premium_krw,
   deductible_krw, deductible_basis, annual_limit_krw, reimbursement_source_url,
   reimbursement_confidence)
SELECT product_id, '견적 기준(플랜 미확인)', NULL, 1, premium_monthly_equiv,
       NULL, NULL, NULL, NULL,
       'UNVERIFIED'
FROM insurance_product
WHERE species = 'DOG'
  AND company_name = '농협손보'
  AND product_name = '(무) NH다이렉트펫앤미든든보험[3종:재가입용]2604';
UPDATE insurance_product
SET product_category = 'MEDICAL',
    age_subject_confidence = 'ASSUMED',
    product_url = 'https://www.nhfire.co.kr/product/retrieveProduct.nhfire?pdtCd=D314512'
WHERE species = 'DOG'
  AND company_name = '농협손보'
  AND product_name = '(무) NH다이렉트펫앤미든든보험[3종:재가입용]2604';

-- ── #5 메리츠화재 / (무) 펫퍼민트 Puppy&Family보험 다이렉트2604
--    노트: 원본 엑셀 비고 70%보장형 자기부담금 3만원 명시. 자기부담금 기준(건당/연간)·적용순서는 원본에 없어 미확인
--    출처: 원본 엑셀 Desktop/펫보험/
INSERT INTO insurance_product_plan_tiers
  (product_id, tier_name, reimbursement_rate_pct, is_reference_tier, premium_krw,
   deductible_krw, deductible_basis, annual_limit_krw, reimbursement_source_url,
   reimbursement_confidence)
SELECT product_id, '70%보장형', 70, 1, premium_monthly_equiv,
       30000, NULL, NULL, '원본 엑셀 Desktop/펫보험/',
       'CONFIRMED_OWN_COVERAGE_NAME'
FROM insurance_product
WHERE species = 'DOG'
  AND company_name = '메리츠화재'
  AND product_name = '(무) 펫퍼민트 Puppy&Family보험 다이렉트2604';
UPDATE insurance_product
SET product_category = 'MEDICAL',
    age_subject_confidence = 'ASSUMED',
    product_url = 'https://store.meritzfire.com/pet/product.do#!/'
WHERE species = 'DOG'
  AND company_name = '메리츠화재'
  AND product_name = '(무) 펫퍼민트 Puppy&Family보험 다이렉트2604';

-- ── #6 DB손보 / (무)다이렉트 펫블리 반려견 의료비보험2605(CM)(재가입용)
--    노트: 원본 엑셀 보장명 (70%보상Ⅱ)(항암), (50%보상)(MRI/CT) — 담보별 상이, 대표값 미정 (Decision 1: 대표값은 후속 단계에서 note로 처리)
--    출처: 원본 엑셀 Desktop/펫보험/
INSERT INTO insurance_product_plan_tiers
  (product_id, tier_name, reimbursement_rate_pct, is_reference_tier, premium_krw,
   deductible_krw, deductible_basis, annual_limit_krw, reimbursement_source_url,
   reimbursement_confidence)
SELECT product_id, '50%보장형', 50, 1, premium_monthly_equiv,
       NULL, NULL, NULL, '원본 엑셀 Desktop/펫보험/',
       'CONFIRMED_OWN_COVERAGE_NAME'
FROM insurance_product
WHERE species = 'DOG'
  AND company_name = 'DB손보'
  AND product_name = '(무)다이렉트 펫블리 반려견 의료비보험2605(CM)(재가입용)';
UPDATE insurance_product
SET product_category = 'MEDICAL',
    age_subject_confidence = 'ASSUMED',
    reimbursement_rate_note = '항암 70% / MRI·CT 50% (담보별 상이, 대표값은 보수적 최저치)',
    product_url = 'https://www.directdb.co.kr/ltm/prd/cpnm/custInfoView.do?searchPdcCd=31054&searchPdcTrtHistCd=00&pdcDvcd=l_pet_dog'
WHERE species = 'DOG'
  AND company_name = 'DB손보'
  AND product_name = '(무)다이렉트 펫블리 반려견 의료비보험2605(CM)(재가입용)';

-- ── #7 카카오페이손해보험 / 무배당 펫보험
--    노트: 미착수 — DOG/CAT 동일 상품명("무배당 펫보험")이므로 매칭 시 종 구분 주의 (계획서 R3)
INSERT INTO insurance_product_plan_tiers
  (product_id, tier_name, reimbursement_rate_pct, is_reference_tier, premium_krw,
   deductible_krw, deductible_basis, annual_limit_krw, reimbursement_source_url,
   reimbursement_confidence)
SELECT product_id, '견적 기준(플랜 미확인)', NULL, 1, premium_monthly_equiv,
       NULL, NULL, NULL, NULL,
       'UNVERIFIED'
FROM insurance_product
WHERE species = 'DOG'
  AND company_name = '카카오페이손해보험'
  AND product_name = '무배당 펫보험';
UPDATE insurance_product
SET product_category = 'MEDICAL',
    age_subject_confidence = 'ASSUMED',
    product_url = 'https://kakaopayinscorp.co.kr/products/PET?dc=DMA100&utm_source=partner_damoa&utm_medium=b2c_damoa&utm_campaign=damoa_pet&utm_content=pet'
WHERE species = 'DOG'
  AND company_name = '카카오페이손해보험'
  AND product_name = '무배당 펫보험';

-- ── #8 현대해상 / (무)현대해상다이렉트굿앤굿우리펫보험(Hi2601) 1종<반려견>_스탠다드플랜
--    노트: 미착수. 자매 상품(#21, CAT 스탠다드플랜)에서 "50/70/80/90% 선택형" 확인됨 — 동일 구조일 가능성 높으나 DOG 상품 자체는 미검증
INSERT INTO insurance_product_plan_tiers
  (product_id, tier_name, reimbursement_rate_pct, is_reference_tier, premium_krw,
   deductible_krw, deductible_basis, annual_limit_krw, reimbursement_source_url,
   reimbursement_confidence)
SELECT product_id, '견적 기준(플랜 미확인)', NULL, 1, premium_monthly_equiv,
       NULL, NULL, NULL, NULL,
       'UNVERIFIED'
FROM insurance_product
WHERE species = 'DOG'
  AND company_name = '현대해상'
  AND product_name = '(무)현대해상다이렉트굿앤굿우리펫보험(Hi2601) 1종<반려견>_스탠다드플랜';
--    product_url: 문서 표기가 축약형이라 DB 기존 값을 유지한다.
UPDATE insurance_product
SET product_category = 'MEDICAL',
    age_subject_confidence = 'ASSUMED'
WHERE species = 'DOG'
  AND company_name = '현대해상'
  AND product_name = '(무)현대해상다이렉트굿앤굿우리펫보험(Hi2601) 1종<반려견>_스탠다드플랜';

-- ── #9 KB손보 / KB 다이렉트 금쪽같은 펫보험(강아지)(무배당)(26.07)
--    노트: 미착수
INSERT INTO insurance_product_plan_tiers
  (product_id, tier_name, reimbursement_rate_pct, is_reference_tier, premium_krw,
   deductible_krw, deductible_basis, annual_limit_krw, reimbursement_source_url,
   reimbursement_confidence)
SELECT product_id, '견적 기준(플랜 미확인)', NULL, 1, premium_monthly_equiv,
       NULL, NULL, NULL, NULL,
       'UNVERIFIED'
FROM insurance_product
WHERE species = 'DOG'
  AND company_name = 'KB손보'
  AND product_name = 'KB 다이렉트 금쪽같은 펫보험(강아지)(무배당)(26.07)';
UPDATE insurance_product
SET product_category = 'MEDICAL',
    age_subject_confidence = 'ASSUMED',
    product_url = 'https://direct.kbinsure.co.kr/home/#/GL/LPD/LT_CM0101M/'
WHERE species = 'DOG'
  AND company_name = 'KB손보'
  AND product_name = 'KB 다이렉트 금쪽같은 펫보험(강아지)(무배당)(26.07)';

-- ── #10 현대해상 / (무)현대해상다이렉트굿앤굿우리펫보험(재가입용)(Hi2605) 1종<반려견>_표준플랜
--    노트: 미착수
INSERT INTO insurance_product_plan_tiers
  (product_id, tier_name, reimbursement_rate_pct, is_reference_tier, premium_krw,
   deductible_krw, deductible_basis, annual_limit_krw, reimbursement_source_url,
   reimbursement_confidence)
SELECT product_id, '견적 기준(플랜 미확인)', NULL, 1, premium_monthly_equiv,
       NULL, NULL, NULL, NULL,
       'UNVERIFIED'
FROM insurance_product
WHERE species = 'DOG'
  AND company_name = '현대해상'
  AND product_name = '(무)현대해상다이렉트굿앤굿우리펫보험(재가입용)(Hi2605) 1종<반려견>_표준플랜';
--    product_url: 문서 표기가 축약형이라 DB 기존 값을 유지한다.
UPDATE insurance_product
SET product_category = 'MEDICAL',
    age_subject_confidence = 'ASSUMED'
WHERE species = 'DOG'
  AND company_name = '현대해상'
  AND product_name = '(무)현대해상다이렉트굿앤굿우리펫보험(재가입용)(Hi2605) 1종<반려견>_표준플랜';

-- ── #11 KB손보 / KB 다이렉트 금쪽같은 펫보험(강아지)(재가입용)(무배당)(26.05)
--    노트: product_url 무효 — 별도 검색 필요 (파일럿 대상 아님, 지시서 명시)
INSERT INTO insurance_product_plan_tiers
  (product_id, tier_name, reimbursement_rate_pct, is_reference_tier, premium_krw,
   deductible_krw, deductible_basis, annual_limit_krw, reimbursement_source_url,
   reimbursement_confidence)
SELECT product_id, '견적 기준(플랜 미확인)', NULL, 1, premium_monthly_equiv,
       NULL, NULL, NULL, NULL,
       'UNVERIFIED'
FROM insurance_product
WHERE species = 'DOG'
  AND company_name = 'KB손보'
  AND product_name = 'KB 다이렉트 금쪽같은 펫보험(강아지)(재가입용)(무배당)(26.05)';
--    ⚠️ product_url 무효('ㅡ' (무효)) — AC-3 미해결.
UPDATE insurance_product
SET product_category = 'MEDICAL',
    age_subject_confidence = 'ASSUMED'
WHERE species = 'DOG'
  AND company_name = 'KB손보'
  AND product_name = 'KB 다이렉트 금쪽같은 펫보험(강아지)(재가입용)(무배당)(26.05)';

-- ── #12 롯데손보 / 댕댕이랑 여행갈땐보험
--    노트: product_category=LIABILITY_TRAVEL 예정(계획서 S4) — 의료비 담보 0건(배상책임/위탁비용/양육자금뿐)이라 환급률 리서치 대상 아님
--    의료비 담보 0건 → 견적 기준 티어를 만들지 않는다(손익분기 대상 아님).
UPDATE insurance_product
SET product_category = 'LIABILITY_TRAVEL',
    age_subject_confidence = 'ASSUMED',
    product_url = 'https://alice.lotteins.co.kr'
WHERE species = 'DOG'
  AND company_name = '롯데손보'
  AND product_name = '댕댕이랑 여행갈땐보험';

-- ── #13 마이브라운 / 강아지만 생각하는 강아지보험
--    노트: product_url 무효 — 별도 검색 필요 (파일럿 대상 아님, 지시서 명시)
INSERT INTO insurance_product_plan_tiers
  (product_id, tier_name, reimbursement_rate_pct, is_reference_tier, premium_krw,
   deductible_krw, deductible_basis, annual_limit_krw, reimbursement_source_url,
   reimbursement_confidence)
SELECT product_id, '견적 기준(플랜 미확인)', NULL, 1, premium_monthly_equiv,
       NULL, NULL, NULL, NULL,
       'UNVERIFIED'
FROM insurance_product
WHERE species = 'DOG'
  AND company_name = '마이브라운'
  AND product_name = '강아지만 생각하는 강아지보험';
--    ⚠️ product_url 무효(www.mybrown.co.kr (스킴 없음, 무효)) — AC-3 미해결.
UPDATE insurance_product
SET product_category = 'MEDICAL',
    age_subject_confidence = 'ASSUMED'
WHERE species = 'DOG'
  AND company_name = '마이브라운'
  AND product_name = '강아지만 생각하는 강아지보험';

-- ── #14 삼성화재 / 무배당 삼성화재 다이렉트 반려묘보험(2601.7)(자동갱신형)
--    노트: 원본 엑셀 보장명에 (50%보상형) ×4담보 명시
--    출처: 원본 엑셀 Desktop/펫보험/
INSERT INTO insurance_product_plan_tiers
  (product_id, tier_name, reimbursement_rate_pct, is_reference_tier, premium_krw,
   deductible_krw, deductible_basis, annual_limit_krw, reimbursement_source_url,
   reimbursement_confidence)
SELECT product_id, '50%보장형', 50, 1, premium_monthly_equiv,
       NULL, NULL, NULL, '원본 엑셀 Desktop/펫보험/',
       'CONFIRMED_OWN_COVERAGE_NAME'
FROM insurance_product
WHERE species = 'CAT'
  AND company_name = '삼성화재'
  AND product_name = '무배당 삼성화재 다이렉트 반려묘보험(2601.7)(자동갱신형)';
UPDATE insurance_product
SET product_category = 'MEDICAL',
    age_subject_confidence = 'ASSUMED',
    product_url = 'http://direct.samsungfire.com/CR_MyAnycarWeb/overture_index.jsp?OTK=PE2304OB100'
WHERE species = 'CAT'
  AND company_name = '삼성화재'
  AND product_name = '무배당 삼성화재 다이렉트 반려묘보험(2601.7)(자동갱신형)';

-- ── #15 카카오페이손해보험 / 무배당 펫보험
--    노트: 미착수 — DOG(#7)와 동일 상품명, 종 구분 주의
INSERT INTO insurance_product_plan_tiers
  (product_id, tier_name, reimbursement_rate_pct, is_reference_tier, premium_krw,
   deductible_krw, deductible_basis, annual_limit_krw, reimbursement_source_url,
   reimbursement_confidence)
SELECT product_id, '견적 기준(플랜 미확인)', NULL, 1, premium_monthly_equiv,
       NULL, NULL, NULL, NULL,
       'UNVERIFIED'
FROM insurance_product
WHERE species = 'CAT'
  AND company_name = '카카오페이손해보험'
  AND product_name = '무배당 펫보험';
--    product_url: 문서 표기가 축약형이라 DB 기존 값을 유지한다.
UPDATE insurance_product
SET product_category = 'MEDICAL',
    age_subject_confidence = 'ASSUMED'
WHERE species = 'CAT'
  AND company_name = '카카오페이손해보험'
  AND product_name = '무배당 펫보험';

-- ── #16 현대해상 / (무)현대해상다이렉트굿앤굿우리펫보험(재가입용)(Hi2605) 2종<반려묘>_표준플랜
--    노트: 미착수
INSERT INTO insurance_product_plan_tiers
  (product_id, tier_name, reimbursement_rate_pct, is_reference_tier, premium_krw,
   deductible_krw, deductible_basis, annual_limit_krw, reimbursement_source_url,
   reimbursement_confidence)
SELECT product_id, '견적 기준(플랜 미확인)', NULL, 1, premium_monthly_equiv,
       NULL, NULL, NULL, NULL,
       'UNVERIFIED'
FROM insurance_product
WHERE species = 'CAT'
  AND company_name = '현대해상'
  AND product_name = '(무)현대해상다이렉트굿앤굿우리펫보험(재가입용)(Hi2605) 2종<반려묘>_표준플랜';
--    product_url: 문서 표기가 축약형이라 DB 기존 값을 유지한다.
UPDATE insurance_product
SET product_category = 'MEDICAL',
    age_subject_confidence = 'ASSUMED'
WHERE species = 'CAT'
  AND company_name = '현대해상'
  AND product_name = '(무)현대해상다이렉트굿앤굿우리펫보험(재가입용)(Hi2605) 2종<반려묘>_표준플랜';

-- ── #17 농협손보 / (무) NH다이렉트펫앤미든든보험[2종:고양이]2604
--    노트: 미착수 — 자매 상품(#3, DOG)에서 "50/70% 선택형" 정보 발견, 동일 구조일 가능성
INSERT INTO insurance_product_plan_tiers
  (product_id, tier_name, reimbursement_rate_pct, is_reference_tier, premium_krw,
   deductible_krw, deductible_basis, annual_limit_krw, reimbursement_source_url,
   reimbursement_confidence)
SELECT product_id, '견적 기준(플랜 미확인)', NULL, 1, premium_monthly_equiv,
       NULL, NULL, NULL, NULL,
       'UNVERIFIED'
FROM insurance_product
WHERE species = 'CAT'
  AND company_name = '농협손보'
  AND product_name = '(무) NH다이렉트펫앤미든든보험[2종:고양이]2604';
UPDATE insurance_product
SET product_category = 'MEDICAL',
    age_subject_confidence = 'ASSUMED',
    product_url = 'https://www.nhfire.co.kr/product/retrieveProduct.nhfire?pdtCd=D314522'
WHERE species = 'CAT'
  AND company_name = '농협손보'
  AND product_name = '(무) NH다이렉트펫앤미든든보험[2종:고양이]2604';

-- ── #18 KB손보 / KB 다이렉트 금쪽같은 펫보험(고양이)(재가입용)(무배당)(26.05)
--    노트: product_url 무효 — 별도 검색 필요 (파일럿 대상 아님, 지시서 명시)
INSERT INTO insurance_product_plan_tiers
  (product_id, tier_name, reimbursement_rate_pct, is_reference_tier, premium_krw,
   deductible_krw, deductible_basis, annual_limit_krw, reimbursement_source_url,
   reimbursement_confidence)
SELECT product_id, '견적 기준(플랜 미확인)', NULL, 1, premium_monthly_equiv,
       NULL, NULL, NULL, NULL,
       'UNVERIFIED'
FROM insurance_product
WHERE species = 'CAT'
  AND company_name = 'KB손보'
  AND product_name = 'KB 다이렉트 금쪽같은 펫보험(고양이)(재가입용)(무배당)(26.05)';
--    ⚠️ product_url 무효('ㅡ' (무효)) — AC-3 미해결.
UPDATE insurance_product
SET product_category = 'MEDICAL',
    age_subject_confidence = 'ASSUMED'
WHERE species = 'CAT'
  AND company_name = 'KB손보'
  AND product_name = 'KB 다이렉트 금쪽같은 펫보험(고양이)(재가입용)(무배당)(26.05)';

-- ── #19 메리츠화재 / (무) 펫퍼민트 Cat&Family보험 다이렉트2604
--    노트: 원본 엑셀 비고 70%보장형 자기부담금 3만원 명시. 자기부담금 기준(건당/연간)·적용순서는 원본에 없어 미확인
--    출처: 원본 엑셀 Desktop/펫보험/
INSERT INTO insurance_product_plan_tiers
  (product_id, tier_name, reimbursement_rate_pct, is_reference_tier, premium_krw,
   deductible_krw, deductible_basis, annual_limit_krw, reimbursement_source_url,
   reimbursement_confidence)
SELECT product_id, '70%보장형', 70, 1, premium_monthly_equiv,
       30000, NULL, NULL, '원본 엑셀 Desktop/펫보험/',
       'CONFIRMED_OWN_COVERAGE_NAME'
FROM insurance_product
WHERE species = 'CAT'
  AND company_name = '메리츠화재'
  AND product_name = '(무) 펫퍼민트 Cat&Family보험 다이렉트2604';
UPDATE insurance_product
SET product_category = 'MEDICAL',
    age_subject_confidence = 'ASSUMED',
    product_url = 'https://store.meritzfire.com/pet/product-cat.do#!/'
WHERE species = 'CAT'
  AND company_name = '메리츠화재'
  AND product_name = '(무) 펫퍼민트 Cat&Family보험 다이렉트2604';

-- ── #20 DB손보 / (무)다이렉트 펫블리 반려묘 의료비보험2605(CM)(재가입용)
--    노트: 미착수
INSERT INTO insurance_product_plan_tiers
  (product_id, tier_name, reimbursement_rate_pct, is_reference_tier, premium_krw,
   deductible_krw, deductible_basis, annual_limit_krw, reimbursement_source_url,
   reimbursement_confidence)
SELECT product_id, '견적 기준(플랜 미확인)', NULL, 1, premium_monthly_equiv,
       NULL, NULL, NULL, NULL,
       'UNVERIFIED'
FROM insurance_product
WHERE species = 'CAT'
  AND company_name = 'DB손보'
  AND product_name = '(무)다이렉트 펫블리 반려묘 의료비보험2605(CM)(재가입용)';
UPDATE insurance_product
SET product_category = 'MEDICAL',
    age_subject_confidence = 'ASSUMED',
    product_url = 'https://www.directdb.co.kr/ltm/prd/cpnmcat/custInfoView.do?searchPdcCd=31067&searchPdcTrtHistCd=00&pdcDvcd=l_pet_cat'
WHERE species = 'CAT'
  AND company_name = 'DB손보'
  AND product_name = '(무)다이렉트 펫블리 반려묘 의료비보험2605(CM)(재가입용)';

-- ── #21 현대해상 / (무)현대해상다이렉트굿앤굿우리펫보험(Hi2601) 2종<반려묘>_스탠다드플랜
--    노트: 파일럿 대상 — 아래 "파일럿 리서치 로그 #2" 참조. 보상비율 50/70/80/90%·자부담 1만/3만/5만원 "선택형" 정보 발견했으나 이 상품(스탠다드플랜)의 실제 선택 조합 특정 불가로 미확정
INSERT INTO insurance_product_plan_tiers
  (product_id, tier_name, reimbursement_rate_pct, is_reference_tier, premium_krw,
   deductible_krw, deductible_basis, annual_limit_krw, reimbursement_source_url,
   reimbursement_confidence)
SELECT product_id, '견적 기준(플랜 미확인)', NULL, 1, premium_monthly_equiv,
       NULL, NULL, NULL, NULL,
       'UNVERIFIED'
FROM insurance_product
WHERE species = 'CAT'
  AND company_name = '현대해상'
  AND product_name = '(무)현대해상다이렉트굿앤굿우리펫보험(Hi2601) 2종<반려묘>_스탠다드플랜';
--    product_url: 문서 표기가 축약형이라 DB 기존 값을 유지한다.
UPDATE insurance_product
SET product_category = 'MEDICAL',
    age_subject_confidence = 'ASSUMED'
WHERE species = 'CAT'
  AND company_name = '현대해상'
  AND product_name = '(무)현대해상다이렉트굿앤굿우리펫보험(Hi2601) 2종<반려묘>_스탠다드플랜';

-- ── #22 DB손보 / (무)다이렉트 펫블리 반려묘보험2607(CM)
--    노트: 미착수
INSERT INTO insurance_product_plan_tiers
  (product_id, tier_name, reimbursement_rate_pct, is_reference_tier, premium_krw,
   deductible_krw, deductible_basis, annual_limit_krw, reimbursement_source_url,
   reimbursement_confidence)
SELECT product_id, '견적 기준(플랜 미확인)', NULL, 1, premium_monthly_equiv,
       NULL, NULL, NULL, NULL,
       'UNVERIFIED'
FROM insurance_product
WHERE species = 'CAT'
  AND company_name = 'DB손보'
  AND product_name = '(무)다이렉트 펫블리 반려묘보험2607(CM)';
UPDATE insurance_product
SET product_category = 'MEDICAL',
    age_subject_confidence = 'ASSUMED',
    product_url = 'https://www.directdb.co.kr/ltm/prd/cpnmcat/custInfoView.do?searchPdcCd=31067&searchPdcTrtHistCd=00&pdcDvcd=l_pet_cat'
WHERE species = 'CAT'
  AND company_name = 'DB손보'
  AND product_name = '(무)다이렉트 펫블리 반려묘보험2607(CM)';

-- ── #23 KB손보 / KB 다이렉트 금쪽같은 펫보험(고양이)(무배당)(26.07)
--    노트: 미착수
INSERT INTO insurance_product_plan_tiers
  (product_id, tier_name, reimbursement_rate_pct, is_reference_tier, premium_krw,
   deductible_krw, deductible_basis, annual_limit_krw, reimbursement_source_url,
   reimbursement_confidence)
SELECT product_id, '견적 기준(플랜 미확인)', NULL, 1, premium_monthly_equiv,
       NULL, NULL, NULL, NULL,
       'UNVERIFIED'
FROM insurance_product
WHERE species = 'CAT'
  AND company_name = 'KB손보'
  AND product_name = 'KB 다이렉트 금쪽같은 펫보험(고양이)(무배당)(26.07)';
UPDATE insurance_product
SET product_category = 'MEDICAL',
    age_subject_confidence = 'ASSUMED',
    product_url = 'https://direct.kbinsure.co.kr/home/#/GL/LPC/LT_CM0101M/'
WHERE species = 'CAT'
  AND company_name = 'KB손보'
  AND product_name = 'KB 다이렉트 금쪽같은 펫보험(고양이)(무배당)(26.07)';

-- ── #24 마이브라운 / 고양이만 생각하는 고양이보험
--    노트: product_url 무효 — 별도 검색 필요 (파일럿 대상 아님, 지시서 명시)
INSERT INTO insurance_product_plan_tiers
  (product_id, tier_name, reimbursement_rate_pct, is_reference_tier, premium_krw,
   deductible_krw, deductible_basis, annual_limit_krw, reimbursement_source_url,
   reimbursement_confidence)
SELECT product_id, '견적 기준(플랜 미확인)', NULL, 1, premium_monthly_equiv,
       NULL, NULL, NULL, NULL,
       'UNVERIFIED'
FROM insurance_product
WHERE species = 'CAT'
  AND company_name = '마이브라운'
  AND product_name = '고양이만 생각하는 고양이보험';
--    ⚠️ product_url 무효(www.mybrown.co.kr (스킴 없음, 무효)) — AC-3 미해결.
UPDATE insurance_product
SET product_category = 'MEDICAL',
    age_subject_confidence = 'ASSUMED'
WHERE species = 'CAT'
  AND company_name = '마이브라운'
  AND product_name = '고양이만 생각하는 고양이보험';

-- ── 생성 요약
--    상품 24건 / 견적 기준 티어 INSERT 23건
--    그중 CONFIRMED 5건 / LIABILITY_TRAVEL 제외 1건
--    → 무결성 쿼리 8 기대값: 23
--    age_subject_confidence 는 전 행 'ASSUMED' — 리서치 표에 열이 없고
--    age_basis 근거를 약관에서 확인한 적이 없다(Principle 1). 무결성 쿼리 3은 통과한다.
-- END GENERATED (S1 backfill)

-- =====================================================================
-- 무결성 쿼리 9종 + 탐지 쿼리 1종 + 신규 쿼리 1종(플랜 티어 유일성, 계획서
-- Verification Steps 3, Expanded Test Plan > Integration 참조). 위 TODO(S1)
-- 백필을 실제로 채운 뒤 실행한다. 10종(1~6, 8~11)은 전부 pass여야 하며 하나라도
-- fail이면 진행을 중단한다. 쿼리 7은 판정 대상이 아니라 "탐지" 쿼리다 — 이 조건에
-- 해당하는 행이 있으면 C-1 재발 위험 구간이므로 상품 목록 화면
-- (/api/insurance/products) 노출 여부를 반드시 별도로 확인해야 한다는 신호일 뿐,
-- SQL 결과 자체가 pass/fail을 내지 않는다.
--
-- ⚠️ 아래 쿼리는 진실 원천이 insurance_product_plan_tiers로 이전된 새 모델에
-- 맞춰 재작성됐다 (worker-1 원안은 insurance_product.reimbursement_rate_pct/
-- reimbursement_confidence를 직접 조회했으나 두 컬럼이 DROP됨).
-- =====================================================================

-- 1) CONFIRMED_OWN_COVERAGE_NAME인데 출처 URL이 없는 티어 → 0건
-- SELECT COUNT(*) FROM insurance_product_plan_tiers
-- WHERE reimbursement_confidence = 'CONFIRMED_OWN_COVERAGE_NAME' AND reimbursement_source_url IS NULL;

-- 2) product_category 미검토(NULL) → 0건 (미검토 + 0행 매칭 동시 탐지, insurance_product는 그대로)
-- SELECT COUNT(*) FROM insurance_product WHERE product_category IS NULL;

-- 3) age_subject_confidence 미검토(NULL) → 0건 (insurance_product는 그대로)
-- SELECT COUNT(*) FROM insurance_product WHERE age_subject_confidence IS NULL;

-- 4) 견적 기준 티어(is_reference_tier=1)의 reimbursement_rate_pct = 80 → 후유장해
--    지급률(80%) 오추출 의심. 있으면 리서치 문서에 근거 URL과 담보명이 병기되어
--    있어야 하며, 없으면 실패로 판정한다.
-- SELECT t.product_id, p.company_name, p.product_name
-- FROM insurance_product_plan_tiers t
-- JOIN insurance_product p ON p.product_id = t.product_id
-- WHERE t.is_reference_tier = 1 AND t.reimbursement_rate_pct = 80;

-- 5) MEDICAL인데 견적 기준 티어의 rate_pct가 NULL(또는 티어 자체가 없음)인 상품
--    개수 → 리서치 문서의 기대치와 정확히 일치해야 함
-- SELECT COUNT(*) FROM insurance_product p
-- LEFT JOIN insurance_product_plan_tiers t
--   ON t.product_id = p.product_id AND t.is_reference_tier = 1
-- WHERE p.product_category = 'MEDICAL' AND (t.reimbursement_rate_pct IS NULL OR t.tier_id IS NULL);

-- 6) product_url이 없거나 http로 시작하지 않는 행 → 0건 (AC-3, insurance_product는 그대로)
-- SELECT COUNT(*) FROM insurance_product WHERE product_url IS NULL OR product_url NOT LIKE 'http%';

-- 7) [탐지 쿼리 — pass/fail 판정 대상 아님]
-- SELECT product_id, company_name, product_name, join_age_min
-- FROM insurance_product
-- WHERE age_subject_confidence = 'CONFIRMED' AND age_basis = 'OWNER' AND join_age_min > 3;
-- (join_age_min > 3은 데모 펫 최대 나이 예시. S3 데모 케이스 확정치로 교체할 것)

-- 8) 사후 총량 검사(0행 매칭/삽입 스킵 탐지, 시나리오 4): 견적 기준 티어가 존재하는
--    상품 개수(백필 완료분) → 리서치 문서의 "완료 건수"와 정확히 일치해야 함.
--    (0행 매칭으로 INSERT가 조용히 스킵됐다면 이 숫자가 문서 기대치보다 작게 나온다)
-- SELECT COUNT(*) FROM insurance_product_plan_tiers WHERE is_reference_tier = 1;

-- 9) confidence ↔ rate 정합: 서로 모순되는 견적 기준 티어 → 0건
-- SELECT COUNT(*) FROM insurance_product_plan_tiers
-- WHERE is_reference_tier = 1
--   AND (reimbursement_confidence = 'UNVERIFIED') <> (reimbursement_rate_pct IS NULL);

-- 10) 연간한도 커버리지: MEDICAL 상품의 견적 기준 티어 중 annual_limit_krw가 NULL인
--     행 개수 → 리서치 문서의 기대치와 정확히 일치해야 함(표시 전용이라 NULL 허용,
--     몇 건인지만 확인)
-- SELECT COUNT(*) FROM insurance_product p
-- JOIN insurance_product_plan_tiers t
--   ON t.product_id = p.product_id AND t.is_reference_tier = 1
-- WHERE p.product_category = 'MEDICAL' AND t.annual_limit_krw IS NULL;

-- 11) 🆕 (worker-4 신규) 플랜 티어 유일성: 견적 기준 티어(is_reference_tier=1)를
--     가진 상품은 반드시 정확히 1개만 가져야 한다 (중복 삽입/재실행 오류 탐지).
--     티어 행이 아예 없는 상품(아직 리서치 미완료)은 이 쿼리의 GROUP BY에
--     나타나지 않으므로 별도로 실패 처리되지 않는다 — 그건 쿼리 5/8이 잡는다.
-- SELECT product_id, SUM(is_reference_tier) AS reference_tier_count
-- FROM insurance_product_plan_tiers
-- GROUP BY product_id
-- HAVING SUM(is_reference_tier) <> 1;
