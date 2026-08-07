-- =====================================================================
-- V12: account_verification에 attempt_count(오답 시도 횟수) 추가 (2026-08-07)
--
-- 1원 인증 입금자명 후보 풀을 378개로 늘렸지만(CodeRabbit 지적), confirm API
-- 자체엔 재시도 횟수 제한이 없어서 한 transaction_id로 계속 찍어보는 방식의
-- 무차별 대입이 여전히 가능했다. 오답을 낼 때마다 이 값을 올리고, 서비스
-- 레이어에서 한도를 넘으면 정답을 넣어도 더 이상 통과시키지 않는다.
--
-- V9/V10은 기부(donation) 기능, V11은 다른 브랜치에서 이미 사용 중이라 번호
-- 충돌을 피하기 위해 V12로 지정한다(조원 공유, 2026-08-07).
-- =====================================================================

ALTER TABLE `account_verification`
    ADD COLUMN `attempt_count` INT NOT NULL DEFAULT 0 COMMENT '입금자명 오답 시도 횟수' AFTER `verification_code`;
