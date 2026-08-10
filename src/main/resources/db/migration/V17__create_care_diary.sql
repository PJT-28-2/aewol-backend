-- 공동육아 일기 (RF-CO)
--
-- 공동육아 참여자가 반려동물의 하루를 날짜별로 기록한다.
-- 조회/작성 권한은 shared_access(status='ACCEPTED') 구성원과 반려동물 소유자를 따르므로
-- 별도의 권한 테이블을 두지 않고 애플리케이션에서 shared_access를 그대로 검사한다.
--
-- V16은 FAQ 시드(PR #65)가 선점하고 있어 V17을 사용한다.

CREATE TABLE IF NOT EXISTS `care_diary` (
    `diary_id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `pet_id`           BIGINT       NOT NULL,
    `author_member_id` BIGINT       NOT NULL COMMENT '작성자 (소유자 또는 공동육아 구성원)',
    -- 어제 일을 오늘 기록할 수 있어야 하므로 작성 시각(created_at)과 분리한다.
    `diary_date`       DATE         NOT NULL COMMENT '기록 대상 날짜',
    `content`          VARCHAR(500) NULL COMMENT '토막글',
    -- 공동 기록이라 실수로 지운 글을 되살릴 수 있도록 soft delete를 쓴다.
    `deleted_at`       DATETIME     NULL,
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`diary_id`),
    KEY `idx_care_diary_pet_date` (`pet_id`, `diary_date` DESC, `diary_id` DESC),
    CONSTRAINT `fk_care_diary_pet` FOREIGN KEY (`pet_id`) REFERENCES `pet` (`pet_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_care_diary_author` FOREIGN KEY (`author_member_id`) REFERENCES `member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 초기에는 일기당 1장만 업로드받지만, 나중에 여러 장으로 늘릴 때 마이그레이션과 API를
-- 함께 고치지 않도록 처음부터 별도 테이블로 분리한다.
CREATE TABLE IF NOT EXISTS `care_diary_image` (
    `image_id`   BIGINT       NOT NULL AUTO_INCREMENT,
    `diary_id`   BIGINT       NOT NULL,
    `image_url`  VARCHAR(500) NOT NULL,
    `sort_order` INT          NOT NULL DEFAULT 0,
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`image_id`),
    KEY `idx_care_diary_image` (`diary_id`, `sort_order`),
    CONSTRAINT `fk_care_diary_image_diary` FOREIGN KEY (`diary_id`) REFERENCES `care_diary` (`diary_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
