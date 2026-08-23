-- gp-perf-bench-core.sql — 측정용 저장 프로시저 정의
--
-- mysql 클라이언트를 매 반복 새로 띄우면(docker exec + 접속 오버헤드) 쿼리 자체보다
-- 프로세스 기동 비용이 커져 ms 단위 차이를 덮어버린다. 그래서 PREPARE/EXECUTE를 MySQL
-- 세션 안에서 반복하고 NOW(6)(마이크로초 정밀도)로 직접 시간을 재는 프로시저를 쓴다.
--
-- 워밍업 5회 + 측정 10회는 docs/group-purchase-list-search-perf-improvement.md 3장의
-- 측정 방법과 동일하다(고정값 — 반복 횟수를 바꾸면 아래 p95 OFFSET 리터럴도 같이 바꿔야
-- 한다). p95는 표본이 10개뿐이라 정확한 백분위수가 아니라 근사치(오름차순 10번째,
-- 사실상 최댓값)다 — 표본이 작을 때 관례적으로 쓰는 방식이며, 여기서는 "꼬리 지연"의
-- 대략적인 감을 보는 용도로만 쓴다.
--
-- 두 가지 MySQL 제약을 피해서 짰다.
--   1) EXECUTE로 SELECT를 실행하면 그 결과 행 자체가 호출자(mysql 클라이언트)에게
--      그대로 반환된다 — 반복마다 결과셋이 출력되어 정작 측정값이 파묻힌다. 그래서
--      측정 대상 쿼리를 `SELECT COUNT(*) INTO @dummy FROM (원래 쿼리) AS q`로 감싼다.
--      ORDER BY/LIMIT가 있는 유도 테이블은 MySQL이 상위 쿼리와 병합하지 않고 그대로
--      실행(정렬·LIMIT 포함)하므로 원래 쿼리와 동일한 실행계획/비용을 유지하면서
--      결과 행은 카운트만 하고 버린다.
--   2) TEMPORARY TABLE은 같은 문장 안에서 두 번 이상 참조할 수 없다("Can't reopen
--      table"). AVG/MIN/MAX/COUNT와 p95(정렬 후 10번째 값)를 한 SELECT에 서브쿼리로
--      합치면 이 제약에 걸리므로, 두 개의 별도 SELECT ... INTO 문으로 나눠서 각각
--      gp_bench_samples를 한 번씩만 참조한다.

DROP PROCEDURE IF EXISTS `gp_bench_run`;

DELIMITER $$

CREATE PROCEDURE `gp_bench_run`(IN label VARCHAR(100), IN query_text TEXT)
BEGIN
    DECLARE i INT DEFAULT 0;
    DECLARE t0 DATETIME(6);
    DECLARE t1 DATETIME(6);
    DECLARE gp_avg DECIMAL(12,2);
    DECLARE gp_p95 DECIMAL(12,2);
    DECLARE gp_min DECIMAL(12,2);
    DECLARE gp_max DECIMAL(12,2);
    DECLARE gp_n INT;

    DROP TEMPORARY TABLE IF EXISTS `gp_bench_samples`;
    CREATE TEMPORARY TABLE `gp_bench_samples` (`elapsed_us` BIGINT);

    SET @gp_bench_sql = CONCAT('SELECT COUNT(*) INTO @gp_bench_dummy FROM (', query_text, ') AS gp_bench_q');

    WHILE i < 5 DO
        PREPARE gp_bench_stmt FROM @gp_bench_sql;
        EXECUTE gp_bench_stmt;
        DEALLOCATE PREPARE gp_bench_stmt;
        SET i = i + 1;
    END WHILE;

    SET i = 0;
    WHILE i < 10 DO
        SET t0 = NOW(6);
        PREPARE gp_bench_stmt FROM @gp_bench_sql;
        EXECUTE gp_bench_stmt;
        DEALLOCATE PREPARE gp_bench_stmt;
        SET t1 = NOW(6);
        INSERT INTO `gp_bench_samples` VALUES (TIMESTAMPDIFF(MICROSECOND, t0, t1));
        SET i = i + 1;
    END WHILE;

    SELECT ROUND(AVG(elapsed_us) / 1000, 2), ROUND(MIN(elapsed_us) / 1000, 2),
           ROUND(MAX(elapsed_us) / 1000, 2), COUNT(*)
    INTO gp_avg, gp_min, gp_max, gp_n
    FROM `gp_bench_samples`;

    -- iterations=10 고정 기준 p95 순위 = CEIL(10*0.95) = 10번째(0-indexed OFFSET 9).
    SELECT ROUND(elapsed_us / 1000, 2) INTO gp_p95
    FROM `gp_bench_samples` ORDER BY elapsed_us LIMIT 1 OFFSET 9;

    DROP TEMPORARY TABLE `gp_bench_samples`;

    SELECT label AS scenario, gp_avg AS avg_ms, gp_p95 AS p95_ms, gp_min AS min_ms, gp_max AS max_ms, gp_n AS n;
END$$

DELIMITER ;
