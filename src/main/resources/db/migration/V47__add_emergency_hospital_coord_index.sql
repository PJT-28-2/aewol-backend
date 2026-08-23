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

-- 24시간 필터를 켠 검색은 따로 인덱스를 둔다.
--
-- 화면에 24시간 토글이 있고, 야간에 응급 병원을 찾는 상황이 이 기능을 쓰는 가장
-- 중요한 순간이다. 그런데 위 인덱스로는 is_24h를 좁히지 못해 후보를 전부 읽은 뒤
-- 걸러낸다.
--
-- 10만 행, 반경 10km 기준 측정.
--   (latitude, longitude)            3,266행 읽고 걸러냄, 7.7ms
--   (is_24h, latitude, longitude)    1,087행, 0.71ms  ← 커버링 인덱스
--
-- 동등 조건을 선두에 두면 인덱스 탐색 범위 자체가 좁아진다.
--
-- 필터를 켜지 않은 기본 경로는 그대로 idx_hospital_coord를 쓴다. 두 인덱스를 함께 두고
-- 확인했고, 옵티마이저가 각 경로에 맞는 쪽을 고른다(기본 경로 2.3ms 유지).
CREATE INDEX `idx_hospital_24h_coord` ON `emergency_hospital` (`is_24h`, `latitude`, `longitude`);
