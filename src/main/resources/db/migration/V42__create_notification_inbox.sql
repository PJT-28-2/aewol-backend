CREATE TABLE IF NOT EXISTS `notification` (
    `notification_id` BIGINT NOT NULL AUTO_INCREMENT,
    `member_id`       BIGINT NOT NULL,
    `type`            VARCHAR(50) NOT NULL,
    `title`           VARCHAR(100) NOT NULL,
    `message`         VARCHAR(500) NOT NULL,
    `target_path`     VARCHAR(500) NULL,
    `read_at`         DATETIME(6) NULL,
    `created_at`      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`notification_id`),
    CONSTRAINT `fk_notification_member`
        FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`) ON DELETE CASCADE,
    INDEX `idx_notification_member_created`
        (`member_id`, `created_at` DESC, `notification_id` DESC),
    INDEX `idx_notification_member_unread`
        (`member_id`, `read_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
