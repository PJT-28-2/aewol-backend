-- 탈퇴 후 30일을 초과한 회원의 PK/FK 연결은 유지하고 로그인 identity와 직접 PII만 제거한다.
ALTER TABLE `member`
    ADD COLUMN `purged_at` DATETIME NULL AFTER `withdrawn_at`;

ALTER TABLE `member`
    MODIFY COLUMN `email` VARCHAR(100) NULL;

ALTER TABLE `member`
    MODIFY COLUMN `name` VARCHAR(20) NULL;

ALTER TABLE `member`
    MODIFY COLUMN `zip_code` VARCHAR(10) NULL;

ALTER TABLE `member`
    MODIFY COLUMN `address` VARCHAR(300) NULL;

CREATE INDEX `idx_member_retention_cleanup`
    ON `member` (`is_active`, `purged_at`, `withdrawn_at`, `member_id`);
