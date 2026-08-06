-- =====================================================================
-- V6: bank_master 시드 데이터
--
-- linked_account / account_verification이 bank_master.bank_code를 FK로
-- 참조하고 있어서(V1, V4), 이 데이터 없이는 계좌 연동/1원인증 INSERT 자체가
-- 실패한다. 프론트 AccountLinkSelect.vue가 보여주는 8개 은행(mocks/account.js
-- MOCK_BANKS) 전부를 채워두되, 실제 CODEF 연동은 KB만 우선 지원한다
-- (나머지는 프론트에서 "준비중"으로 비활성 처리됨).
--
-- bank_code는 금융결제원 표준 3자리 은행코드를 그대로 쓴다. 프론트
-- utils/bankMeta.js의 BANK_CODE_ALIASES가 이미 이 코드 체계를 별도 매핑 없이
-- 인식하도록 만들어져 있다(004→KB, 088→신한, 020→우리, 081→하나, 011→NH,
-- 090→카카오뱅크, 092→토스뱅크, 003→IBK). CODEF 기관코드는 이 3자리 앞에
-- '0'을 붙인 4자리 값과 동일하다(예: 004 -> CODEF organization "0004") —
-- CodefClient에서 호출 시점에 패딩한다.
-- =====================================================================

INSERT INTO `bank_master` (`bank_code`, `bank_name`) VALUES
    ('004', 'KB국민은행'),
    ('092', '토스뱅크'),
    ('088', '신한은행'),
    ('081', '하나은행'),
    ('020', '우리은행'),
    ('011', 'NH농협은행'),
    ('003', 'IBK기업은행'),
    ('090', '카카오뱅크')
AS new_bank
ON DUPLICATE KEY UPDATE `bank_name` = new_bank.`bank_name`;
