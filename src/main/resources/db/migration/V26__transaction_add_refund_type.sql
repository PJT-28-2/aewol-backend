-- 환불 거래(공동구매 참여 취소, 마감 미달 자동환불)를 지갑 충전과 구분하기 위해 txn_type에 REFUND를 추가한다.
-- REFUND는 counter_wallet_id/recurring_id 조건이 DEPOSIT/WITHDRAW와 동일하다(내부 이체·정기결제와 무관한 단순 입금).
ALTER TABLE `transaction`
    DROP CHECK `chk_txn_type_consistency`;

ALTER TABLE `transaction`
    ADD CONSTRAINT `chk_txn_type_consistency` CHECK (
        (`txn_type` = 'TRANSFER' AND `counter_wallet_id` IS NOT NULL AND `recurring_id` IS NULL)
        OR (`txn_type` = 'PAYMENT' AND `counter_wallet_id` IS NULL)
        OR (`txn_type` IN ('DEPOSIT', 'WITHDRAW', 'REFUND') AND `counter_wallet_id` IS NULL AND `recurring_id` IS NULL)
    );
