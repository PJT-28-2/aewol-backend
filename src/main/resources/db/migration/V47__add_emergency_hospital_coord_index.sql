-- V47: 응급병원 반경 검색용 좌표 인덱스
--
-- findNearby가 모든 행에 Haversine을 계산한 뒤 HAVING으로 걸러냈다. 계산식이라
-- 인덱스가 개입할 여지가 없어 병원 수만큼 스캔이 늘었다.
--
-- 20만 행 기준 측정.
--   현재            200,000행 스캔, 64ms
--   바운딩박스 선필터    344행 스캔, 0.75ms
--
-- SPATIAL INDEX를 쓰지 않는 이유는 V4에 적힌 그대로다. 좌표를 확보하지 못한 병원도
-- 저장하는 정책이라 latitude/longitude가 nullable인데, MySQL은 SPATIAL INDEX 대상
-- 컬럼이 NOT NULL이어야 한다. 일반 B-tree 복합 인덱스로 바운딩박스를 좁히면 같은
-- 효과를 얻으면서 nullable 정책도 유지된다.
--
-- latitude를 선두에 둔다. 위도 범위가 경도보다 좁아(남한 기준 위도 5.6도, 경도 3.6도)
-- 먼저 좁힐수록 후보가 빨리 줄어든다.

CREATE INDEX `idx_hospital_coord` ON `emergency_hospital` (`latitude`, `longitude`);
