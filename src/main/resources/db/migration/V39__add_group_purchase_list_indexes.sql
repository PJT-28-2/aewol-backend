-- V39: 공동구매 목록 조회용 인덱스 추가
--
-- 목록은 가장 자주 열리는 화면인데 인덱스가 PRIMARY(gp_id)와 idx_gp_author(member_id)
-- 둘뿐이라 조회 경로가 쓸 수 있는 인덱스가 없었다. EXPLAIN이 type=ALL, Extra=Using
-- filesort로 나왔다.
--
-- 20만 행을 넣고 측정한 결과다(로컬 MySQL 8).
--
--   진행중 탭      101ms -> 2.7ms    20만 행 스캔 -> 20행 인덱스 스캔
--   카테고리 필터  풀스캔 -> 0.84ms
--
-- created_at DESC로 만드는 이유는 목록 정렬이 항상 최신순이기 때문이다. MySQL 8은
-- 내림차순 인덱스를 실제로 역순 저장하므로 정렬을 그대로 넘길 수 있다.
--
-- 전체 탭(상태 필터 없음)은 이 인덱스로 개선되지 않는다. 정렬식에 NOW()가 들어가
-- 어떤 인덱스도 순서를 제공할 수 없고, 같은 이유로 함수 인덱스도 만들 수 없다.
-- 그 경로는 여전히 풀스캔이며 별도 과제로 남긴다.

CREATE INDEX `idx_gp_created` ON `group_purchase` (`created_at` DESC);

-- 카테고리 탭은 등호 조건이라 (category, created_at) 순서로 두면 필터와 정렬을 한
-- 인덱스로 처리한다.
CREATE INDEX `idx_gp_category_created` ON `group_purchase` (`category`, `created_at` DESC);
