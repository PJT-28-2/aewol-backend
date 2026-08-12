-- 간편 비밀번호(송금/이체 확인용 6자리 PIN)를 저장할 컬럼을 추가한다.
-- 로그인 비밀번호(password)와 별개 값이며, 같은 방식(BCrypt)으로 해시해서 저장한다.
-- 최초 계좌 연동 완료 후 1회 설정하는 것을 기본 흐름으로 하되, 재설정(덮어쓰기)도 같은 컬럼을 사용한다.
ALTER TABLE `member`
    ADD COLUMN `simple_password` VARCHAR(255) NULL COMMENT '간편 비밀번호(6자리 PIN, BCrypt 해시) - 송금/이체 확인용' AFTER `password`;
