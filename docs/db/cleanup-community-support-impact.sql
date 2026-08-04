-- DEV_TEST_ONLY: seed-community-support-impact.sql로 넣은 데이터만 FK 역순으로 제거한다.
-- 시드는 9000번대 명시 ID(BIGINT) / 'seed-%' 자연키를 사용한다.
START TRANSACTION;

DELETE FROM `donation_history` WHERE `donation_id` BETWEEN 9000 AND 9999;
DELETE FROM `donation_roundup` WHERE `roundup_id` BETWEEN 9000 AND 9999;
DELETE FROM `member_donation_preference` WHERE `member_id` BETWEEN 9000 AND 9999;
DELETE FROM `donation_setting` WHERE `member_id` BETWEEN 9000 AND 9999;
DELETE FROM `donation_campaign` WHERE `campaign_id` BETWEEN 9000 AND 9999;
DELETE FROM `donation_channel` WHERE `channel_id` BETWEEN 9000 AND 9999;
DELETE FROM `donation_organization` WHERE `organization_id` BETWEEN 9000 AND 9999;

DELETE FROM `support_program_interest` WHERE `interest_id` BETWEEN 9000 AND 9999;
DELETE FROM `local_support_program_condition` WHERE `program_condition_id` BETWEEN 9000 AND 9999;
DELETE FROM `local_support_program` WHERE `program_id` BETWEEN 9000 AND 9999;
DELETE FROM `gov24_public_service_support_condition` WHERE `service_id` LIKE 'seed-%';
DELETE FROM `gov24_public_service_detail` WHERE `service_id` LIKE 'seed-%';
DELETE FROM `gov24_public_service` WHERE `service_id` LIKE 'seed-%';

DELETE FROM `activity_log` WHERE `log_id` BETWEEN 9000 AND 9999;
DELETE FROM `shared_access` WHERE `access_id` BETWEEN 9000 AND 9999;
DELETE FROM `transaction` WHERE `txn_id` BETWEEN 9000 AND 9999;
DELETE FROM `pet` WHERE `pet_id` BETWEEN 9000 AND 9999;
DELETE FROM `wallet` WHERE `wallet_id` BETWEEN 9000 AND 9999;
DELETE FROM `member` WHERE `member_id` BETWEEN 9000 AND 9999 AND `email` LIKE '%@example.test';

COMMIT;
