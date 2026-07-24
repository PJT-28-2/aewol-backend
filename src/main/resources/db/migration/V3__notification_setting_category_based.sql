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
--
-- 주의: 기존 사용자의 push_enabled/email_enabled 값을 그냥 버리면 알림 선호도가
-- 사라지므로, 새 컬럼을 NULL로 추가 → 기존 값 백필 → 그제서야 NOT NULL/기본값
-- 확정 + 레거시 컬럼 삭제 순서로 진행.
-- ================================================================

-- V3-1. 새 카테고리 컬럼을 일단 NULL 허용으로만 추가 (기존 행 안전)
ALTER TABLE `notification_setting`
    ADD COLUMN `payment_enabled`           TINYINT(1) NULL AFTER `member_id`,
    ADD COLUMN `recurring_payment_enabled` TINYINT(1) NULL AFTER `payment_enabled`,
    ADD COLUMN `family_share_enabled`      TINYINT(1) NULL AFTER `recurring_payment_enabled`,
    ADD COLUMN `community_enabled`         TINYINT(1) NULL AFTER `family_share_enabled`;

-- V3-2. 기존 push_enabled/email_enabled 값을 새 컬럼으로 백필.
-- 예전엔 "채널"(push/email) 단위로만 켜고 껐지 카테고리별 세부 설정이
-- 없었으므로, 완전히 정확한 1:1 매핑은 불가능함. 대신 사용자의 원래 의도
-- (알림을 받고 싶어했는지 여부)를 최대한 보존하는 방식으로 이관:
--   push_enabled 또는 email_enabled 중 하나라도 켜져 있었다면
--   → 그 사용자는 "알림을 받고 싶다"는 의도였다고 보고 카테고리 전부 ON
--   둘 다 꺼져 있었다면(알림을 완전히 꺼둔 사용자) → 카테고리 전부 OFF
UPDATE `notification_setting`
SET `payment_enabled`           = IF(`push_enabled` = 1 OR `email_enabled` = 1, 1, 0),
    `recurring_payment_enabled` = IF(`push_enabled` = 1 OR `email_enabled` = 1, 1, 0),
    `family_share_enabled`      = IF(`push_enabled` = 1 OR `email_enabled` = 1, 1, 0),
    `community_enabled`         = IF(`push_enabled` = 1 OR `email_enabled` = 1, 1, 0);

-- V3-3. 백필 끝났으니 NOT NULL + 기본값(신규 가입자용) 확정, 레거시 컬럼 제거
ALTER TABLE `notification_setting`
    MODIFY COLUMN `payment_enabled`           TINYINT(1) NOT NULL DEFAULT 1
        COMMENT '결제 알림 — 결제 시 알림',
    MODIFY COLUMN `recurring_payment_enabled` TINYINT(1) NOT NULL DEFAULT 1
        COMMENT '정기 결제 알림 — 정기결제일 3일 전 알림',
    MODIFY COLUMN `family_share_enabled`      TINYINT(1) NOT NULL DEFAULT 1
        COMMENT '가족 공유 알림 — 공동양육 가족의 입출금·지출 변경 알림',
    MODIFY COLUMN `community_enabled`         TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '커뮤니티 알림 — 공동구매 마감·참여현황 알림',
    MODIFY COLUMN `marketing_enabled`         TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '마케팅 정보 수신 — 혜택·이벤트 소식' AFTER `community_enabled`,
    DROP COLUMN `push_enabled`,
    DROP COLUMN `email_enabled`;

-- 결과 컬럼 순서: member_id, payment_enabled, recurring_payment_enabled,
-- family_share_enabled, community_enabled, marketing_enabled, updated_at
-- (화면에 나온 순서 그대로)
