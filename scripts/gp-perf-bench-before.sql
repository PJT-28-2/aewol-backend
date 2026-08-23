-- gp-perf-bench-before.sql — 현재(개선 전) findList 쿼리 5개 시나리오 측정
-- GroupPurchaseMapper.xml의 findList와 동일한 조건절/정렬을 리터럴 값으로 고정해 재현한다.
-- gp-perf-bench-core.sql을 먼저 로드해야 한다(gp-perf-bench.sh가 순서대로 실행).
--
-- size=10(서비스 기본 페이지 크기) 기준 LIMIT 11(=size+1, hasNext 판정용)을 그대로 쓴다.
-- 키워드는 시드 스크립트가 137행마다 심어둔 '츄르', 카테고리는 '사료'를 쓴다.

-- ── EXPLAIN (실행계획은 반복 측정할 필요가 없어 1회씩만 별도로 찍는다) ──────────────

EXPLAIN SELECT gp_id, member_id, product_name, category, image, unit_price, group_price,
    target_quantity, current_quantity, status, deadline, created_at
FROM group_purchase
WHERE status != 'CANCELLED' AND target_quantity > 0
  AND product_name LIKE '%츄르%'
ORDER BY CASE WHEN current_quantity < target_quantity AND deadline >= NOW() THEN 0 ELSE 1 END,
    deadline ASC, created_at DESC, gp_id DESC
LIMIT 11 OFFSET 0;

EXPLAIN SELECT gp_id, member_id, product_name, category, image, unit_price, group_price,
    target_quantity, current_quantity, status, deadline, created_at
FROM group_purchase
WHERE status != 'CANCELLED' AND target_quantity > 0
  AND category = '사료'
  AND product_name LIKE '%츄르%'
ORDER BY CASE WHEN current_quantity < target_quantity AND deadline >= NOW() THEN 0 ELSE 1 END,
    deadline ASC, created_at DESC, gp_id DESC
LIMIT 11 OFFSET 0;

EXPLAIN SELECT gp_id, member_id, product_name, category, image, unit_price, group_price,
    target_quantity, current_quantity, status, deadline, created_at
FROM group_purchase
WHERE status != 'CANCELLED' AND target_quantity > 0
  AND current_quantity < target_quantity AND deadline >= NOW()
ORDER BY deadline ASC, created_at DESC, gp_id DESC
LIMIT 11 OFFSET 0;

EXPLAIN SELECT gp_id, member_id, product_name, category, image, unit_price, group_price,
    target_quantity, current_quantity, status, deadline, created_at
FROM group_purchase
WHERE status != 'CANCELLED' AND target_quantity > 0
  AND current_quantity < target_quantity AND deadline >= NOW()
ORDER BY deadline ASC, created_at DESC, gp_id DESC
LIMIT 11 OFFSET 1000;

EXPLAIN SELECT gp_id, member_id, product_name, category, image, unit_price, group_price,
    target_quantity, current_quantity, status, deadline, created_at
FROM group_purchase
WHERE status != 'CANCELLED' AND target_quantity > 0
ORDER BY CASE WHEN current_quantity < target_quantity AND deadline >= NOW() THEN 0 ELSE 1 END,
    deadline ASC, created_at DESC, gp_id DESC
LIMIT 11 OFFSET 0;

-- ── 타이밍 측정 (웜업 5 + 측정 10) ──────────────────────────────────────────────

CALL gp_bench_run('키워드 검색 (keyword, status 없음)',
    'SELECT gp_id, member_id, product_name, category, image, unit_price, group_price, target_quantity, current_quantity, status, deadline, created_at FROM group_purchase WHERE status != ''CANCELLED'' AND target_quantity > 0 AND product_name LIKE ''%츄르%'' ORDER BY CASE WHEN current_quantity < target_quantity AND deadline >= NOW() THEN 0 ELSE 1 END, deadline ASC, created_at DESC, gp_id DESC LIMIT 11 OFFSET 0');

CALL gp_bench_run('키워드 검색 + 카테고리 필터',
    'SELECT gp_id, member_id, product_name, category, image, unit_price, group_price, target_quantity, current_quantity, status, deadline, created_at FROM group_purchase WHERE status != ''CANCELLED'' AND target_quantity > 0 AND category = ''사료'' AND product_name LIKE ''%츄르%'' ORDER BY CASE WHEN current_quantity < target_quantity AND deadline >= NOW() THEN 0 ELSE 1 END, deadline ASC, created_at DESC, gp_id DESC LIMIT 11 OFFSET 0');

CALL gp_bench_run('목록 조회 1페이지 (offset=0, status=OPEN)',
    'SELECT gp_id, member_id, product_name, category, image, unit_price, group_price, target_quantity, current_quantity, status, deadline, created_at FROM group_purchase WHERE status != ''CANCELLED'' AND target_quantity > 0 AND current_quantity < target_quantity AND deadline >= NOW() ORDER BY deadline ASC, created_at DESC, gp_id DESC LIMIT 11 OFFSET 0');

CALL gp_bench_run('목록 조회 딥 페이지 (100페이지, offset=1000, status=OPEN)',
    'SELECT gp_id, member_id, product_name, category, image, unit_price, group_price, target_quantity, current_quantity, status, deadline, created_at FROM group_purchase WHERE status != ''CANCELLED'' AND target_quantity > 0 AND current_quantity < target_quantity AND deadline >= NOW() ORDER BY deadline ASC, created_at DESC, gp_id DESC LIMIT 11 OFFSET 1000');

CALL gp_bench_run('전체 탭 (상태 필터 없음)',
    'SELECT gp_id, member_id, product_name, category, image, unit_price, group_price, target_quantity, current_quantity, status, deadline, created_at FROM group_purchase WHERE status != ''CANCELLED'' AND target_quantity > 0 ORDER BY CASE WHEN current_quantity < target_quantity AND deadline >= NOW() THEN 0 ELSE 1 END, deadline ASC, created_at DESC, gp_id DESC LIMIT 11 OFFSET 0');
