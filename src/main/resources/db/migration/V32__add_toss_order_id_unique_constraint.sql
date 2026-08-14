-- Toss 충전 주문은 한 개의 원장 행만 생성할 수 있다.
-- MySQL은 UNIQUE 컬럼의 NULL을 여러 개 허용하므로 비-Toss 거래에는 영향을 주지 않는다.
ALTER TABLE `transaction`
    ADD CONSTRAINT `uk_txn_order_id` UNIQUE (`order_id`);
