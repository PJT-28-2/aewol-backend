-- 참여 취소 이력을 보존하기 위해 row를 삭제하지 않고 payment_status='CANCELLED' + canceled_at으로 남긴다.
-- 기존 (gp_id, member_id) UNIQUE(V14)를 그대로 두면 취소 이력 row가 재참여 INSERT와 충돌하므로,
-- CANCELLED 행은 유니크 판정에서 빠지도록 생성 컬럼 + 부분 유니크 인덱스로 교체한다.
-- MySQL은 UNIQUE 인덱스에서 NULL을 서로 다른 값으로 취급하므로, CANCELLED 행마다 NULL이 들어가
-- 같은 회원이 같은 gp_id를 몇 번을 취소·재참여해도 충돌하지 않는다.
ALTER TABLE `group_purchase_participant`
    ADD COLUMN `canceled_at` DATETIME NULL COMMENT '참여 취소 시각',
    ADD COLUMN `active_member_id` BIGINT
        GENERATED ALWAYS AS (IF(`payment_status` = 'CANCELLED', NULL, `member_id`)) STORED
        COMMENT 'CANCELLED가 아닌 활성 참여만 유니크 판정에 포함시키는 생성 컬럼';

ALTER TABLE `group_purchase_participant`
    DROP INDEX `uq_gpp_gp_member`,
    ADD CONSTRAINT `uq_gpp_gp_active_member` UNIQUE (`gp_id`, `active_member_id`);
