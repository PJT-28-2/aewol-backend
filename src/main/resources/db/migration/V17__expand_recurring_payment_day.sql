-- 정기결제 희망일을 29~31일까지 허용한다.
-- 해당 날짜가 없는 달은 애플리케이션에서 그달의 마지막 날로 계산한다.
ALTER TABLE `recurring_payment`
    DROP CHECK `chk_recurring_payment_day`;

ALTER TABLE `recurring_payment`
    MODIFY COLUMN `payment_day` INT NOT NULL COMMENT '매월 결제 희망일 (1~31, 없는 날짜는 말일 처리)',
    ADD CONSTRAINT `chk_recurring_payment_day`
        CHECK (`payment_day` BETWEEN 1 AND 31);
