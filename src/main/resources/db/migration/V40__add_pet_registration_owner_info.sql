-- V40: pet_registration에 검증 시 사용한 소유자 이름/생년월일 저장
--
-- 지금까지는 verify() 요청으로 받은 owner_nm/owner_birth를 APMS 호출에만 쓰고 버려서,
-- 재연동(이미 검증된 등록증을 다시 verify)할 때 이 값을 다시 알 방법이 없었다. 그 결과
-- 프론트가 임시로 로그인 회원 이름을 대신 채워 보내고 있었는데, "앱 등록자 != 정부 등록
-- 소유자"인 경우(가족 반려동물 대리 등록 등) 매번 조회에 실패했다.
--
-- 이 값은 pet 등록 시점이 아니라 APMS 검증이 성공한 시점에 확정되는 값이라 pet이 아닌
-- pet_registration에 둔다. 해제(cancel) 시 이 행 자체가 삭제되므로 별도 정리 로직도
-- 필요 없다.
ALTER TABLE `pet_registration`
    ADD COLUMN `owner_name`  VARCHAR(50) NULL COMMENT 'APMS 조회에 사용한 소유자 성명' AFTER `apr_gbn_nm`,
    ADD COLUMN `owner_birth` VARCHAR(8)  NULL COMMENT 'APMS 조회에 사용한 소유자 생년월일(yyyyMMdd)' AFTER `owner_name`;
