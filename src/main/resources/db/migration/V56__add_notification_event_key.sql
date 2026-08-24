-- V56: 알림 이벤트 멱등 키.
--
-- 정기결제 3일 전 알림은 next_payment_date만 보고 만들고, Redis 잡 잠금은 실행이 끝나면
-- 풀린다. 다른 서버가 잠금 해제 뒤 따라 들어오거나, 같은 날 수동 재실행하면 같은 알림이
-- 또 생긴다. event_key UNIQUE로 같은 이벤트는 한 행만 남긴다.
--
-- NULL은 UNIQUE에서 서로 다른 값으로 취급되므로, 키가 없는 기존 알림은 그대로 둔다.
--
-- V53~V55는 다른 열린 PR(#412, #414)이 이미 쓰고 있어 건너뛴다.
ALTER TABLE `notification`
    ADD COLUMN `event_key` VARCHAR(120) NULL
        COMMENT '이벤트 멱등 키. 같은 알림을 중복 저장하지 않는다'
        AFTER `target_path`,
    ADD UNIQUE KEY `uk_notification_event_key` (`event_key`);
