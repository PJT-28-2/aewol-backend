-- V44: 멍스타그램 1차 — 일기 공개 여부, 신고, 반려동물 인스타 핸들
--
-- 공동육아 일기를 공개해 다른 사용자도 볼 수 있게 한다. 계정 주체는 사람이 아니라
-- 반려동물이라, 공개면에 사람 이름이 나가지 않는다.
--
-- 기본값을 PRIVATE으로 두는 것이 이 마이그레이션의 핵심이다. 지금까지 쓰인 일기는
-- 전부 "가족만 본다"는 전제로 작성됐다. 집 내부, 아이 얼굴, 산책 경로가 들어 있을 수
-- 있어 소급 공개는 구조적으로 불가능해야 한다.

ALTER TABLE `care_diary`
    ADD COLUMN `visibility` VARCHAR(10) NOT NULL DEFAULT 'PRIVATE'
        COMMENT 'PRIVATE(가족만) / PUBLIC(멍스타그램 공개)' AFTER `version`,
    -- 작성자가 스스로 내린 것과 신고로 내려간 것을 구분한다. 한 컬럼으로 섞으면
    -- 작성자가 다시 공개로 돌려 신고를 무력화할 수 있다.
    ADD COLUMN `hidden_by_report_at` DATETIME NULL
        COMMENT '신고로 노출이 중단된 시각. 값이 있으면 작성자도 다시 공개할 수 없다' AFTER `visibility`;

-- 탐색 피드는 공개된 일기를 최신순으로 훑는다. 공개 행이 전체의 일부일 것이므로
-- visibility를 선두에 두어 후보를 먼저 좁힌다.
CREATE INDEX `idx_care_diary_public` ON `care_diary` (`visibility`, `created_at` DESC);

-- 반려동물이 계정 주체이므로 외부 인스타 핸들도 반려동물에 붙는다. 선택 항목이다.
ALTER TABLE `pet`
    ADD COLUMN `instagram_id` VARCHAR(30) NULL COMMENT '반려동물 인스타그램 핸들(@ 제외)' AFTER `character_img`;

-- 신고는 고객센터 문의로 이어진다(inquiry_id). 별도 운영 도구를 만들지 않고 기존
-- 문의 흐름을 재사용하기 위한 연결이다.
CREATE TABLE IF NOT EXISTS `diary_report` (
    `report_id`   BIGINT      NOT NULL AUTO_INCREMENT,
    `diary_id`    BIGINT      NOT NULL,
    `reporter_id` BIGINT      NOT NULL COMMENT '신고한 회원',
    -- 문의 생성이 실패해도 신고 자체는 남아야 하므로 NULL을 허용한다.
    `inquiry_id`  BIGINT      NULL COMMENT '연결된 고객센터 문의',
    `reason`      VARCHAR(30) NOT NULL,
    `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`report_id`),
    -- 같은 사람이 같은 일기를 여러 번 신고해도 한 건으로 센다.
    UNIQUE KEY `uk_diary_report_reporter` (`diary_id`, `reporter_id`),
    KEY `idx_diary_report_diary` (`diary_id`),
    CONSTRAINT `fk_diary_report_diary` FOREIGN KEY (`diary_id`) REFERENCES `care_diary` (`diary_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_diary_report_member` FOREIGN KEY (`reporter_id`) REFERENCES `member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
