-- gp-perf-cleanup.sql — gp-perf-seed.sql로 넣은 시드 데이터 제거
-- 시드 전용 회원(gp-perf-seed@example.test)이 작성한 group_purchase 행만 지운다.
-- 참여자(group_purchase_participant)는 시드 스크립트가 만들지 않으므로 대상이 없다.

SET @seed_member_id = (SELECT `member_id` FROM `member` WHERE `email` = 'gp-perf-seed@example.test');

DELETE FROM `group_purchase` WHERE `member_id` = @seed_member_id;
DELETE FROM `member` WHERE `member_id` = @seed_member_id;

SELECT ROW_COUNT() AS note;
