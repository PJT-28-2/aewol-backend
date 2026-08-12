-- 정부24 `전화문의`는 다른 기관 필드와 달리 여러 지자체 연락처를
-- `시명/번호||시명/번호` 형태로 한 필드에 이어붙여 내려준다.
-- 경기도처럼 시군이 많은 광역 단위 서비스는 VARCHAR(200)을 넘겨
-- 마지막 전화번호가 반토막 난 채로 저장됐다(#128).
--
-- 인덱스가 걸려 있지 않은 컬럼이라 TEXT 확장에 제약이 없다.
ALTER TABLE `gov24_public_service`
    MODIFY COLUMN `contact_phone` TEXT NULL COMMENT '전화문의';
