-- 지원사업의 지역을 광역 단위에서 시군구 단위로 되돌린다.
--
-- 동기화가 기관명의 첫 어절만 남기는 바람에 구민 전용 사업이 그 시도 주민 전체에게
-- 신청 가능으로 보였다. 원본(gov24_public_service.organization_name)에는 시군구가
-- 그대로 있으므로 거기서 다시 만든다.
--
-- 재동기화로도 고쳐지지만 GOV24_API_KEY가 없는 팀원은 동기화를 돌릴 수 없다.
-- V36 시드로 받은 27건이 그대로 광역 단위로 남으므로 여기서 함께 갱신한다.
--
-- 둘째 어절이 늘 시군구인 것은 아니다. "서울특별시 동물복지과"처럼 부서명이 오면
-- 광역 단위 사업으로 남겨야 해서 시/군/구로 끝나는 값만 붙인다.
-- (자바 쪽 Gov24SyncServiceImpl.extractRegion과 같은 규칙이다.)

-- 1) 큐레이션 테이블의 region
UPDATE local_support_program p
JOIN gov24_public_service g ON g.service_id = p.source_service_id
SET p.region = CONCAT(
        SUBSTRING_INDEX(g.organization_name, ' ', 1),
        ' ',
        SUBSTRING_INDEX(SUBSTRING_INDEX(g.organization_name, ' ', 2), ' ', -1))
WHERE g.organization_name LIKE '% %'
  AND SUBSTRING_INDEX(g.organization_name, ' ', 1) REGEXP '(특별시|광역시|특별자치시|특별자치도|도)$'
  AND SUBSTRING_INDEX(SUBSTRING_INDEX(g.organization_name, ' ', 2), ' ', -1) REGEXP '(시|군|구)$';

-- 2) 자동 생성된 지역 조건. 화면에 그대로 노출되는 문구라 함께 맞춘다.
--    MANUAL 조건은 운영자가 넣은 것이므로 건드리지 않는다.
UPDATE local_support_program_condition c
JOIN local_support_program p ON p.program_id = c.program_id
SET c.condition_value = p.region,
    c.title = CONCAT(p.region, ' 거주자'),
    c.description = CONCAT('회원 주소가 ', p.region, '이어야 합니다.')
WHERE c.condition_type = 'REGION'
  AND p.region IS NOT NULL
  AND p.region <> '';
