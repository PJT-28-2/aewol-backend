-- =====================================================================
-- V32: 상품당 견적 기준 티어(is_reference_tier=1) 1개 제약
--
-- V30이 insurance_product_plan_tiers의 is_reference_tier=1 행을 환급률·
-- 자기부담금·연간한도·출처의 진실 원천으로 승격했지만, "상품당 1개"라는 불변조건은
-- V30 하단의 주석 처리된 무결성 쿼리 11로만 존재했다. 주석은 아무 데서도 실행되지
-- 않으므로 실제로는 아무것도 막지 못한다.
--
-- 깨지는 경로가 구체적으로 있다: V30 자신이 "새 근거가 나오면 새 마이그레이션을
-- 만들어 티어를 갱신하라"고 안내하는데, 그 작성자가 기존 기준 티어를 내리는 걸
-- 잊고 새 행만 INSERT하면 그 상품에 기준 티어가 2개가 된다. 그러면
-- InsuranceSimulationServiceImpl의 toMap이 IllegalStateException을 던져 해당 종의
-- 시뮬레이션 요청 전체가 500이 됐다(코드 쪽에도 병합 함수를 넣어 방어했지만,
-- 방어 코드는 잘못된 데이터가 들어오는 것 자체를 막지 못한다).
--
-- MySQL은 부분 인덱스(CREATE UNIQUE INDEX ... WHERE)를 지원하지 않으므로,
-- 생성 컬럼으로 같은 효과를 낸다: is_reference_tier=1인 행만 product_id 값을
-- 갖고 나머지는 NULL이 되며, MySQL의 UNIQUE 인덱스는 NULL 중복을 허용한다.
-- 따라서 기준 티어가 아닌 티어(플랜 옵션 행)는 상품당 몇 개든 그대로 둘 수 있다.
--
-- 적용 전 확인: is_reference_tier=1 기준 중복 product_id 0건
--   SELECT product_id, COUNT(*) FROM insurance_product_plan_tiers
--    WHERE is_reference_tier = 1 GROUP BY product_id HAVING COUNT(*) > 1;
-- 중복이 있으면 이 마이그레이션은 실패한다. 그 경우 어느 티어가 견적 기준인지
-- 사람이 판단해 나머지의 is_reference_tier를 0으로 내린 뒤 다시 적용한다 —
-- 어느 쪽을 남길지는 데이터 소유자만 알 수 있으므로 자동으로 정리하지 않는다.
--
-- ⚠️ 이 파일은 develop에 V32가 없음을 확인하고 붙인 번호다. 리뷰 중 develop에
-- 새 마이그레이션이 들어오면 V30/V31과 마찬가지로 다시 밀어야 한다.
-- =====================================================================

ALTER TABLE insurance_product_plan_tiers
  ADD COLUMN reference_tier_product_id BIGINT
    GENERATED ALWAYS AS (IF(is_reference_tier = 1, product_id, NULL)) VIRTUAL
    COMMENT '견적 기준 티어 유니크 제약용 파생 컬럼(기준 티어가 아니면 NULL)',
  ADD UNIQUE KEY uk_plan_tier_reference_per_product (reference_tier_product_id);
