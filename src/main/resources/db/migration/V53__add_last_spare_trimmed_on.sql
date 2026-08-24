-- 매일 한 번만 MAIN 잔액을 절삭해 저금통으로 옮긴다.
ALTER TABLE `donation_setting`
    ADD COLUMN `last_spare_trimmed_on` DATE NULL
        COMMENT 'YYYY-MM-DD. 일 1회 잔액 절삭 이체 멱등성'
        AFTER `last_auto_donated_year_month`;
