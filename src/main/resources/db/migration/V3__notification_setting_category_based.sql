-- ================================================================
-- V3 — 알림 설정 화면 개편 반영
-- notification_setting: 채널 기반(push/email) → 카테고리 기반으로 전환
-- ================================================================
-- 화면 기준(전체 알림 / 결제 알림 / 정기 결제 알림 / 가족 공유 알림 /
-- 커뮤니티 알림 / 마케팅 정보 수신) 매핑.
--
-- "전체 알림"은 DB 컬럼으로 안 둠 — 아래 카테고리 5개를 한 번에 켜고 끄는
-- UI 전용 마스터 토글이라, 별도로 저장할 상태가 없음(다섯 개 중 하나라도
-- OFF면 화면에서 "전체 알림"도 OFF로 보여주면 됨. 프론트에서 계산).
-- ================================================================

ALTER TABLE `notification_setting`
    DROP COLUMN `push_enabled`,
    DROP COLUMN `email_enabled`,
    ADD COLUMN `payment_enabled`           TINYINT(1) NOT NULL DEFAULT 1
        COMMENT '결제 알림 — 결제 시 알림' AFTER `member_id`,
    ADD COLUMN `recurring_payment_enabled` TINYINT(1) NOT NULL DEFAULT 1
        COMMENT '정기 결제 알림 — 정기결제일 3일 전 알림' AFTER `payment_enabled`,
    ADD COLUMN `family_share_enabled`      TINYINT(1) NOT NULL DEFAULT 1
        COMMENT '가족 공유 알림 — 공동양육 가족의 입출금·지출 변경 알림' AFTER `recurring_payment_enabled`,
    ADD COLUMN `community_enabled`         TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '커뮤니티 알림 — 공동구매 마감·참여현황 알림' AFTER `family_share_enabled`,
    MODIFY COLUMN `marketing_enabled`      TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '마케팅 정보 수신 — 혜택·이벤트 소식' AFTER `community_enabled`;

-- 결과 컬럼 순서: member_id, payment_enabled, recurring_payment_enabled,
-- family_share_enabled, community_enabled, marketing_enabled, updated_at
-- (화면에 나온 순서 그대로)
