-- V38: 공동육아 일기에 낙관락용 version 컬럼 추가
--
-- 같은 일기를 두 곳에서 동시에 수정하면 나중 저장이 앞선 저장을 조용히 덮어썼다.
-- update의 WHERE에 버전 조건이 없어 동시 요청이 둘 다 성공했기 때문이다.
--
-- updated_at을 버전으로 쓰지 않는 이유는 DATETIME이라 초 단위이기 때문이다. 같은 초
-- 안에 들어온 두 수정은 값이 같아 충돌을 구분하지 못한다. 정수 카운터를 따로 둔다.
--
-- 기존 행은 0에서 시작한다. 클라이언트가 version을 보내지 않으면 서버가 버전 검사를
-- 건너뛰므로(선택 값), 이 마이그레이션만 먼저 배포해도 기존 앱이 깨지지 않는다.

ALTER TABLE `care_diary`
    ADD COLUMN `version` BIGINT NOT NULL DEFAULT 0
    COMMENT '낙관락 버전. 수정할 때마다 1씩 증가한다' AFTER `content`;
