-- V42: 공동구매 목록/마이페이지 정렬을 "마감 임박순(deadline 오름차순), 동률 시 최신순"으로 변경하면서
-- 그 정렬을 뒷받침하는 인덱스로 교체한다.
--
-- V39가 만든 idx_gp_created/idx_gp_category_created는 "목록 정렬이 항상 최신순"이라는 전제로
-- 만들어졌는데(V39 주석 참고), 이번에 정렬 기준을 deadline으로 바꾸면서 그 전제가 깨졌다.
-- created_at 단일/선두 인덱스로는 새 정렬(deadline, created_at DESC, gp_id DESC)에 순서를 제공할 수 없어
-- filesort로 되돌아가므로, 인덱스도 정렬 기준에 맞춰 같이 바꾼다.
DROP INDEX `idx_gp_created` ON `group_purchase`;
DROP INDEX `idx_gp_category_created` ON `group_purchase`;

CREATE INDEX `idx_gp_deadline_created` ON `group_purchase` (`deadline`, `created_at` DESC, `gp_id` DESC);

-- 카테고리 탭은 등호 조건이라 (category, deadline, created_at, gp_id) 순서로 두면 필터와 정렬을 한
-- 인덱스로 처리한다(V39의 idx_gp_category_created와 동일한 이유).
CREATE INDEX `idx_gp_category_deadline_created` ON `group_purchase` (`category`, `deadline`, `created_at` DESC, `gp_id` DESC);
