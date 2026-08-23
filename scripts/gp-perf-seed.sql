-- gp-perf-seed.sql — 공동구매 목록 성능 측정용 20만 행 시드
-- (docs/group-purchase-list-search-perf-improvement.md 3장 측정 방법 1단계)
--
-- V39 마이그레이션 주석의 측정 조건(로컬 MySQL 8, 20만 행)을 그대로 재현한다.
-- 실행/정리는 scripts/gp-perf-bench.sh 를 통해서 하는 것을 권장한다(직접 실행해도 무방).
--
-- 멱등성: member는 email UNIQUE로 ON DUPLICATE KEY UPDATE 처리한다. group_purchase
-- 시드 행에는 자연키가 없어 재실행하면 중복 삽입된다 — gp-perf-bench.sh가 재실행 전
-- 행 수를 확인해 이미 20만 행이면 건너뛴다. 직접 재실행하려면 먼저
-- gp-perf-cleanup.sql로 지운 뒤 실행할 것.
--
-- 분포 설계(각 시나리오가 실제 쿼리와 같은 실행계획을 타도록):
--   - product_name: 137행마다 1개꼴로 검색 키워드('츄르') 포함, 나머지는 미포함
--     (풀스캔 시 대부분을 읽고 소수만 반환하는 실제 키워드 검색 패턴)
--   - category: 사료/간식/용품/기타 4종 균등 순환
--   - 진행중 40% / 완료 30% / 마감실패 30% (findList의 OPEN/COMPLETED/FAILED 판정 기준과 동일)
--   - deadline/created_at: 과거~미래 365일, 730시간에 걸쳐 분산 → 딥 페이지네이션(offset=1000)이
--     실제로 여러 행을 건너뛰게 만든다

SET SESSION cte_max_recursion_depth = 1100;

-- 시드 전용 회원(FK 충족용). 실제 로그인에 쓰이지 않으므로 password는 NULL.
-- zip_code/address는 NOT NULL이라 더미 값을 채운다(member.email_active 유니크는 email과
-- 무관하게 파생되므로 재실행해도 충돌하지 않는다).
INSERT INTO `member` (`email`, `password`, `name`, `provider`, `role`, `zip_code`, `address`)
VALUES ('gp-perf-seed@example.test', NULL, 'GP성능시드', 'LOCAL', 'USER', '00000', '(벤치 시드 전용, 실주소 아님)')
ON DUPLICATE KEY UPDATE `member_id` = LAST_INSERT_ID(`member_id`);
SET @seed_member_id = LAST_INSERT_ID();

DROP TEMPORARY TABLE IF EXISTS `seq_1k`;
CREATE TEMPORARY TABLE `seq_1k` (`n` INT PRIMARY KEY);
INSERT INTO `seq_1k`
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 1000
)
SELECT n FROM seq;

DROP TEMPORARY TABLE IF EXISTS `seq_200`;
CREATE TEMPORARY TABLE `seq_200` (`n` INT PRIMARY KEY);
INSERT INTO `seq_200`
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 200
)
SELECT n FROM seq;

-- seq_1k(1000) x seq_200(200) = 정확히 20만 행. 필터 없이 카티션 곱만으로 rn을 만든다.
INSERT INTO `group_purchase`
    (`member_id`, `product_name`, `category`, `unit_price`, `group_price`,
     `target_quantity`, `current_quantity`, `status`, `is_urgent_active`, `deadline`, `created_at`)
SELECT
    @seed_member_id,
    CASE WHEN MOD(rn, 137) = 0
         THEN CONCAT('[벤치시드] 프리미엄 츄르 구독팩 ', rn)
         ELSE CONCAT('[벤치시드] 테스트 상품 ', rn)
    END,
    ELT(1 + MOD(rn, 4), '사료', '간식', '용품', '기타'),
    10000.00,
    9000.00,
    tgt,
    -- 완료(30%, MOD 4~6): 목표 달성. 진행중/마감실패(70%): 목표 미달.
    CASE WHEN MOD(rn, 10) BETWEEN 4 AND 6 THEN tgt ELSE FLOOR(tgt / 2) END,
    'OPEN',
    -- V49: is_urgent_active는 DB가 자동 계산해주지 않는 파생 컬럼이라 여기서도 실제 서비스와
    -- 같은 규칙(current_quantity < target_quantity AND deadline >= NOW())을 반영해야 한다.
    -- 컬럼 자체를 빼먹으면 NOT NULL DEFAULT 0이 전 행에 조용히 들어가 "전체 탭" 벤치마크가
    -- is_urgent_active 분기 없이 도는 것처럼 되어버린다(리뷰로 발견, 재측정 계기).
    -- 완료(MOD 4~6)와 마감실패(MOD 7~9)는 urgent가 아니고, 나머지(진행중, MOD 0~3)만 urgent다.
    CASE WHEN MOD(rn, 10) BETWEEN 4 AND 9 THEN 0 ELSE 1 END,
    -- 마감실패(30%, MOD 7~9): 과거 마감. 진행중/완료(70%): 미래 마감.
    CASE WHEN MOD(rn, 10) BETWEEN 7 AND 9
         THEN DATE_SUB(NOW(), INTERVAL (1 + MOD(rn, 365)) DAY)
         ELSE DATE_ADD(NOW(), INTERVAL (1 + MOD(rn, 365)) DAY)
    END,
    DATE_SUB(NOW(), INTERVAL MOD(rn, 730) HOUR)
FROM (
    SELECT
        (b.n - 1) * 1000 + a.n AS rn,
        10 + MOD((b.n - 1) * 1000 + a.n, 20) AS tgt
    FROM `seq_1k` a JOIN `seq_200` b
) t;

DROP TEMPORARY TABLE IF EXISTS `seq_1k`;
DROP TEMPORARY TABLE IF EXISTS `seq_200`;

SELECT COUNT(*) AS seeded_rows FROM `group_purchase` WHERE `member_id` = @seed_member_id;
