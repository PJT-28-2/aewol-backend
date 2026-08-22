-- V48: 상태 필터 없는 "전체" 탭 정렬 풀스캔 해결.
--
-- 기존 정렬은 ORDER BY CASE WHEN current_quantity < target_quantity AND deadline >= NOW()
-- THEN 0 ELSE 1 END, deadline ASC, created_at DESC, gp_id DESC 였다. NOW()가 정렬식에 있어
-- 어떤 인덱스도 순서를 제공할 수 없고 함수 인덱스 대상도 될 수 없다(V39/V42에 "별도 과제"로
-- 명시됨). 실측(20만 행): avg 225.03ms, type=ALL + Using filesort.
--
-- CASE가 매 요청 NOW()로 계산하던 값을 is_urgent_active 컬럼에 미리 저장해두고 정렬에
-- 그 컬럼을 쓴다. current_quantity/target_quantity 변화(참여/취소)는 해당 서비스 트랜잭션에서
-- 즉시 갱신하고, deadline이 NOW()를 지나는 시점(그 행에 대한 쓰기 이벤트가 아니라 트리거로는
-- 못 잡음)은 매일 자정 직후 배치(GroupPurchaseUrgentFlagJob)가 갱신한다. 프론트가 deadline을
-- 항상 그날 23:59:59로 보내는 것을 확인했고, 서버도 저장 시점에 그 값을 강제하므로(deadline이
-- 자정 경계에서만 바뀐다는 불변식) 배치는 하루 1회로 충분하다.
ALTER TABLE `group_purchase`
    ADD COLUMN `is_urgent_active` TINYINT(1) NOT NULL DEFAULT 0 AFTER `deadline`;

UPDATE `group_purchase`
SET `is_urgent_active` = 1
WHERE `current_quantity` < `target_quantity`
  AND `deadline` >= NOW();

-- ORDER BY is_urgent_active DESC, deadline ASC, created_at DESC, gp_id DESC 를 그대로
-- 커버한다. 상태 필터가 있는 탭(OPEN/COMPLETED/FAILED)은 기존 idx_gp_deadline_created /
-- idx_gp_category_deadline_created를 그대로 쓰므로 건드리지 않는다 — 그쪽은 이미 필터로
-- 좁혀진 뒤라 CASE를 빼도 결과가 같아서(GroupPurchaseMapper.xml 주석) V39/V42로 해결된 상태다.
CREATE INDEX `idx_gp_urgent_deadline_created`
    ON `group_purchase` (`is_urgent_active` DESC, `deadline` ASC, `created_at` DESC, `gp_id` DESC);
