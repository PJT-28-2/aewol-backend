-- =====================================================================
-- V34: 금감원 규제 근거 백필 + age_basis 오기재 정정
--
-- 이슈: #178
-- 근거 문서: docs/insurance-reimbursement-research.md 5~7절
-- 명세: .omc/specs/deep-interview-insurance-reimbursement-backfill.md
--
-- ---------------------------------------------------------------------
-- 왜 이 마이그레이션이 필요한가
-- ---------------------------------------------------------------------
-- V30이 환급률의 진실 원천을 plan_tiers로 옮겼지만, 24개 상품 중 5건만
-- 확정되고 18건이 UNVERIFIED로 남았다. 2026-08-14에 1차 원천(원본 엑셀
-- 4파일 24건)을 셀 단위로 전수 재검증한 결과 **환급률이 명시된 건 5건이
-- 전부**이고 나머지는 보장명에 비율 표기가 아예 없음이 확정됐다. 웹은
-- 견적 플로우 뒤 SPA이고 약관 PDF는 ECONNRESET으로 막힌다.
-- 즉 개별 리서치로는 더 나오지 않는다.
--
-- 대신 금융감독원 감독행정(2025.5.1 시행)이 **전 상품에 강제하는 한계값**을
-- 기록한다. 이것은 추측이 아니라 "아무리 유리해도 이 이상은 아니다"라는
-- 참인 진술이다 — 계획서 Principle 1(추측값을 확정값으로 저장하지 않는다)을
-- 어기지 않는다.
--
--   자기부담금   최소 30,000원 (면제 불가)
--   자기부담률   30% 이상  → 보장비율(환급률) 70% 이하
--   재가입주기   1년
--   보험금 산식  "치료비에서 자기부담금을 공제한 후 자기부담률을 적용"
--                → 선차감(BEFORE_RATE)
--
-- 적용 대상 확인: DB 상품의 상품코드가 2601·2604·2605·2607(2026년 판)이므로
-- 2025.5.1 시행 이후 상품이다.
--
-- 출처 URL 주의: 이 감독행정은 비공개 행정지도라 금감원 원문 공개 URL이 없다.
-- 아래 source_url은 이를 보도한 수의 전문지(데일리벳) 기사이며, 1차 자료가
-- 아니라 2차 보도임을 밝혀 둔다. 금감원 원문을 확보하면 갱신할 것.
--
-- ---------------------------------------------------------------------
-- ⚠️ 이 마이그레이션이 화면에 미치는 영향 (반드시 확인할 것)
-- ---------------------------------------------------------------------
-- 1) breakEvenAvailable=true 상품이 5건 → 21건으로 늘어난다.
--    buildAdvice()의 verdict 분모와 대표 상품 선정이 통째로 바뀐다.
--    적용 전후 DOG/CAT verdict를 각각 기록할 것 (명세 AC-10).
--
-- 2) 여기서 채우는 70%는 **확인값이 아니라 상한**이다.
--    화면에 "환급률 70%"로 단언하면 거짓 표시가 된다.
--    "최대 70%(규제 상한)"처럼 상한임이 드러나야 한다 (명세 AC-8,
--    프론트 이슈 aewol-frontend#253).
--
-- 3) deductible_basis는 NULL로 남긴다(아래 3번 문단 참조). 따라서
--    calculateAnnualReimbursement의 basis 가드에 걸려 자기부담금은
--    여전히 계산에 반영되지 않고 deductibleApplied=false로 나간다.
--    이 마이그레이션은 자기부담금을 **기록**할 뿐 **반영**하지 않는다.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. deductible_order = 'BEFORE_RATE' (전 상품)
--
-- 현재 24건 전부 NULL이다. NULL이면 effectiveAnnualDeductible()이
-- 자기부담금을 반영하지 않으므로, 나중에 basis가 확정돼도 order가 비어
-- 있으면 계산이 여전히 안 돈다.
--
-- 금감원이 보험금 산식을 "치료비에서 자기부담금을 공제한 후 자기부담률을
-- 적용"으로 정의했으므로 선차감(BEFORE_RATE)이 업계 표준이다. 개별 약관에서
-- 다른 순서가 확인되면 그 상품만 후속 마이그레이션으로 덮어쓴다.
--
-- IS NULL 조건: 이미 값이 있는 행(향후 개별 확인분)을 덮어쓰지 않는다.
--
-- product_category='MEDICAL' 조건: 아래 3번과 같은 대상으로 맞춘다. 롯데손보
-- 여행자보험(LIABILITY_TRAVEL)은 의료비 담보가 0건이라 자기부담금/보험금 산식
-- 자체가 성립하지 않는다. 계산에 영향은 없지만, 금감원 펫보험 감독행정을 근거로
-- 쓰는 이상 그 규제가 적용되지 않는 상품에까지 값을 심을 이유가 없다.
-- ---------------------------------------------------------------------
UPDATE insurance_product
   SET deductible_order = 'BEFORE_RATE'
 WHERE deductible_order IS NULL
   AND product_category = 'MEDICAL';


-- ---------------------------------------------------------------------
-- 2. age_basis 오기재 정정 — 현대해상 재가입용 2건 (DOG/CAT)
--
-- InsuranceProductPolicy.isEligibleByAge는 age_basis='OWNER'를 나이와
-- 무관하게 항상 적격으로 판정한다. 그런데 현대해상 재가입용(Hi2605,
-- 가입연령 1~19)은 펫 나이 기준 상품인데 OWNER로 백필돼 있어,
-- **0세 펫에게도 노출된다**(로컬 API로 실증).
--
--   GET /api/insurance/products?petType=DOG&age=0
--   → (무)현대해상다이렉트굿앤굿우리펫보험(재가입용)(Hi2605) 1종<반려견>_표준플랜
--     | 가입연령 1~19   ← 나와서는 안 됨
--
-- 같은 1~19 패턴의 다른 재가입용은 이미 PET로 올바르다
-- (DB손보 2605, KB손보 26.05 — KB손보는 원본 비고에 "연령은 반려동물의
-- 연령을 의미"가 명시돼 있다). 재가입용 7건 중 OWNER인 것은 현대해상 2건뿐이다.
--
-- 한편 현대해상 **신규**(Hi2601, 19~80)는 OWNER가 맞다 — 신규가입 최소
-- 19세를 펫 나이로 읽으면 성립하지 않는다. 그래서 조건에 '재가입용'을 넣었다.
--
-- age_subject_confidence는 ASSUMED로 그대로 둔다. CONFIRMED로 올리면
-- InsuranceProductServiceImpl.isConfirmedAgeMismatch의 하드 필터가 깨어나
-- 상품이 카탈로그에서 배제될 수 있다 (계획서 Critic C-1과 같은 구조의 회귀).
-- ---------------------------------------------------------------------
UPDATE insurance_product
   SET age_basis = 'PET'
 WHERE company_name = '현대해상'
   AND product_name LIKE '%재가입용%'
   AND age_basis = 'OWNER';


-- ---------------------------------------------------------------------
-- 3. 규제 하한 백필 — UNVERIFIED 견적 기준 티어 16건
--
-- 채우는 값과 근거:
--   reimbursement_rate_pct = 70     자기부담률 30% 이상 → 환급률 상한
--   deductible_krw        = 30000   자기부담금 최소 3만원
--   reimbursement_confidence = 'REGULATORY_BOUND'   (신규 값)
--
-- deductible_basis는 **NULL로 남긴다.** 금감원 문서는 금액 하한만 정하고
-- 건당(PER_VISIT)/연간(PER_YEAR)을 규정하지 않는다. 모르는 값을 채우지
-- 않는다 — 계획서 Principle 1. 그 결과 자기부담금은 계산에 반영되지 않고
-- 화면에 "자기부담금 미반영"이 유지된다(명세 AC-5·AC-9의 의도된 조합).
--
-- 제외 대상 2종:
--   (a) 마이브라운 DOG/CAT 2건 — 보장이 '기본치료비 15만원 /
--       수술 및 특정고액치료비 200만원'으로 타사(1,000만~4,000만원) 대비
--       자릿수가 두 자리 낮다. **정액형으로 의심**되며, 정액형이면 환급률
--       개념 자체가 없어 70% 적용이 틀린 처리가 된다. UNVERIFIED 유지.
--   (b) product_category <> 'MEDICAL' — 롯데손보 여행자보험(LIABILITY_TRAVEL).
--       애초에 견적 기준 티어가 없지만 조건으로 명시해 의도를 남긴다.
--
-- WHERE에 confidence='UNVERIFIED'를 두어 확정 5건(CONFIRMED_OWN_COVERAGE_NAME)을
-- 덮어쓰지 않는다. 상품 ID를 나열하지 않고 데이터 조건으로 거르는 이유는,
-- insurance_product 시드를 재적재하면 AUTO_INCREMENT PK가 재부여되기
-- 때문이다(V3:179-182, V31 헤더 참조).
--
-- 적용 전 확인 (2026-08-14 로컬):
--   대상 16건 / 마이브라운 제외 2건 / rate가 이미 있는 UNVERIFIED 0건
-- ---------------------------------------------------------------------
UPDATE insurance_product_plan_tiers t
  JOIN insurance_product p ON p.product_id = t.product_id
   SET t.reimbursement_rate_pct   = 70,
       t.deductible_krw           = 30000,
       t.reimbursement_confidence = 'REGULATORY_BOUND',
       t.reimbursement_source_url = 'https://www.dailyvet.co.kr/news/industry/242198'
 WHERE t.is_reference_tier = 1
   AND t.reimbursement_confidence = 'UNVERIFIED'
   AND p.product_category = 'MEDICAL'
   AND p.company_name <> '마이브라운';


-- ---------------------------------------------------------------------
-- 4. confidence 컬럼 COMMENT에 REGULATORY_BOUND 추가
-- ---------------------------------------------------------------------
ALTER TABLE insurance_product_plan_tiers
  MODIFY COLUMN reimbursement_confidence VARCHAR(30) NULL
  COMMENT 'CONFIRMED_OWN_COVERAGE_NAME(원본 보장명 확인) / ASSUMED_FROM_RESEARCH / REGULATORY_BOUND(금감원 규제 상한·하한, 확인값 아님) / UNVERIFIED';


-- =====================================================================
-- 무결성 확인 쿼리 (적용 후 수동 실행 — 주석이므로 자동 실행되지 않는다)
-- =====================================================================
--
-- Q1. 규제 하한 행에 출처가 빠진 것이 없어야 한다 (기대: 0)
--   SELECT COUNT(*) FROM insurance_product_plan_tiers
--    WHERE reimbursement_confidence = 'REGULATORY_BOUND'
--      AND (reimbursement_source_url IS NULL OR reimbursement_source_url = '');
--
-- Q2. 규제 하한 행의 rate는 반드시 70이어야 한다 (기대: 0)
--   SELECT COUNT(*) FROM insurance_product_plan_tiers
--    WHERE reimbursement_confidence = 'REGULATORY_BOUND' AND reimbursement_rate_pct <> 70;
--
-- Q3. 규제 하한 행의 deductible_basis는 NULL이어야 한다 (기대: 0)
--   SELECT COUNT(*) FROM insurance_product_plan_tiers
--    WHERE reimbursement_confidence = 'REGULATORY_BOUND' AND deductible_basis IS NOT NULL;
--
-- Q4. 마이브라운은 UNVERIFIED로 남아 있어야 한다 (기대: 2)
--   SELECT COUNT(*) FROM insurance_product_plan_tiers t
--     JOIN insurance_product p ON p.product_id = t.product_id
--    WHERE p.company_name = '마이브라운' AND t.reimbursement_confidence = 'UNVERIFIED';
--
-- Q5. 확정 5건이 덮어써지지 않았어야 한다 (기대: 5)
--   SELECT COUNT(*) FROM insurance_product_plan_tiers
--    WHERE reimbursement_confidence = 'CONFIRMED_OWN_COVERAGE_NAME';
--
-- Q6. 의료비 상품 중 deductible_order가 NULL인 것이 없어야 한다 (기대: 0)
--     LIABILITY_TRAVEL 1건은 의도적으로 NULL로 남으므로 카테고리를 좁혀서 센다.
--   SELECT COUNT(*) FROM insurance_product
--    WHERE deductible_order IS NULL AND product_category = 'MEDICAL';
--
-- Q7. 가입연령이 1로 시작하는 재가입용 중 age_basis=OWNER가 없어야 한다 (기대: 0)
--     위 2번 UPDATE는 회사를 현대해상으로 좁혔지만(오기재가 실증된 범위만 건드린다),
--     이 불변식은 회사 무관이다. 다른 회사에서 같은 오기재가 생기면 여기서 잡힌다.
--   SELECT COUNT(*) FROM insurance_product
--    WHERE product_name LIKE '%재가입용%' AND join_age_min >= 1 AND age_basis = 'OWNER';
--
-- Q8. 종별 계산 가능 상품 수 (verdict 분모 변화 기록용)
--   SELECT p.species, COUNT(*) FROM insurance_product p
--     JOIN insurance_product_plan_tiers t ON t.product_id = p.product_id AND t.is_reference_tier = 1
--    WHERE p.product_category = 'MEDICAL' AND t.reimbursement_rate_pct IS NOT NULL
--    GROUP BY p.species;
-- =====================================================================
