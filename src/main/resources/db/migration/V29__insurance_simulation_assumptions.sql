-- =====================================================================
-- V29: insurance_simulation에 가정치·대표상품·조정여부 컬럼 추가
--
-- 계획 문서: .omc/plans/pet-insurance-simulator-fix-consensus.md (v3.1) S5
--
-- ⚠️ 파일명 순연 안내: 계획서상 원래 파일명은 V28이었으나, V27__insurance_reimbursement_
-- and_category.sql이 저장소의 기존 V27과 충돌해 V28로 순연되면서 이 파일도 V29로
-- 한 칸씩 밀렸다 (자세한 사유는 V28__insurance_reimbursement_and_category.sql 상단 참조).
-- ALTER 문/컬럼/내용은 계획서와 동일하고, 버전 숫자만 다르다.
--
-- representative_product_id에는 FK를 걸지 않는다 — insurance_product 시드를
-- 재적재하면 AUTO_INCREMENT PK가 재부여되므로(V3:179-182), 과거 시뮬레이션
-- 이력이 가리키던 product_id가 다른 상품을 가리키게 되거나 끊길 수 있다.
-- 대신 회사명+상품명 스냅샷을 representative_product_label에 함께 저장해
-- ID가 바뀌어도 "그 시점에 어떤 상품이 대표였는지"를 사람이 읽을 수 있게
-- 보존한다. 이것은 의도적인 설계 선택이다(정규화 위반이 아니라 이력 안정성 우선).
--
-- V2:199-208에서 DROP한 컬럼명(estimated_annual_cost / premium / deductible /
-- break_even_year)과 이름이 겹치지 않음을 확인했다 — 컬럼 재추가가 아니라
-- 새로운 목적의 신규 컬럼이다.
-- =====================================================================

ALTER TABLE insurance_simulation
  ADD COLUMN assumed_annual_medical_cost_krw DECIMAL(15,2) NULL,
  ADD COLUMN assumption_source               VARCHAR(200)  NULL,
  ADD COLUMN representative_product_id       BIGINT        NULL,
  ADD COLUMN representative_product_label    VARCHAR(250)  NULL COMMENT '회사명+상품명 스냅샷(ID 재부여 대비)',
  ADD COLUMN is_user_adjusted                TINYINT(1)    NOT NULL DEFAULT 0;
