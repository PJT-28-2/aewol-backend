ALTER TABLE `diary_report`
    ADD COLUMN `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' AFTER `reason`,
    ADD COLUMN `resolution` VARCHAR(20) NULL AFTER `status`,
    ADD COLUMN `admin_note` VARCHAR(500) NULL AFTER `resolution`,
    ADD COLUMN `resolved_by` BIGINT NULL AFTER `admin_note`,
    ADD COLUMN `resolved_at` DATETIME NULL AFTER `resolved_by`,
    ADD KEY `idx_diary_report_status_created` (`status`, `created_at` DESC),
    ADD CONSTRAINT `fk_diary_report_resolved_by`
        FOREIGN KEY (`resolved_by`) REFERENCES `member` (`member_id`) ON DELETE SET NULL;
