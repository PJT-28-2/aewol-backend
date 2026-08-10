-- AI 반려동물 캐릭터 이미지 (RF-CM)
--
-- 사진을 올리면 생성형 모델이 두 종류의 이미지를 만든다.
--   character_img : 홈 화면에 세우는 전신 3D 마스코트
--   profile_img   : 목록·프로필에 쓰는 정면 얼굴
-- 두 이미지의 쓰임과 구도가 달라 컬럼을 분리한다.
--
-- V1의 pet 테이블에는 이미지 컬럼이 없었다. member에만 profile_img가 있어
-- PetServiceImpl이 pet.profile_img를 읽어도 항상 null이 나오던 상태를 함께 바로잡는다.
--
-- V16은 FAQ 시드(PR #65), V17은 공동육아 일기(#87)가 사용한다.

ALTER TABLE `pet`
    ADD COLUMN `profile_img`   VARCHAR(500) NULL COMMENT 'AI 생성 정면 얼굴 이미지 경로' AFTER `medical_history`,
    ADD COLUMN `character_img` VARCHAR(500) NULL COMMENT 'AI 생성 전신 캐릭터 이미지 경로' AFTER `profile_img`;
