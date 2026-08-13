-- 홈 화면 AI 인사이트 카드 캐시.
--
-- 홈은 앱을 열 때마다 뜨고 LLM 호출은 매번 비용과 지연이 붙는다. 결과를 회원·카드
-- 종류별로 저장해 두고, 배치가 새벽에 미리 채운다. 캐시가 없을 때만 그 자리에서
-- 만든다(read-through).
CREATE TABLE IF NOT EXISTS `home_insight`
(
    `insight_id`   BIGINT       NOT NULL AUTO_INCREMENT,
    `member_id`    BIGINT       NOT NULL,
    -- 카드마다 데이터 출처가 달라 따로 만들고 따로 만료된다.
    `card_type`    VARCHAR(20)  NOT NULL COMMENT 'SUPPORT / SPENDING / CARE / DONATION',
    `headline`     VARCHAR(100) NOT NULL COMMENT '숫자가 들어가는 제목. 데이터로 직접 만든다',
    `body`         VARCHAR(300) NOT NULL COMMENT 'LLM이 생성한 2문장',
    `cta_label`    VARCHAR(30)      NULL,
    `cta_path`     VARCHAR(100)     NULL,
    -- 생성 근거가 된 데이터 스냅샷. 같은 입력이면 다시 부르지 않도록 비교에 쓰고,
    -- 이상한 문구가 나왔을 때 무엇을 보고 썼는지 추적하는 데도 쓴다.
    `source_digest` VARCHAR(64)     NULL,
    -- LLM 호출 없이 데이터로 만든 대체 문구인지. 외부 장애 시에도 카드는 떠야 한다.
    `fallback`     CHAR(1)      NOT NULL DEFAULT 'N',
    `generated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `expires_at`   DATETIME     NOT NULL,
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`insight_id`),
    -- 회원당 카드 종류별로 최신 1건만 유지한다. upsert 가 이 키를 탄다.
    UNIQUE KEY `uk_home_insight_member_card` (`member_id`, `card_type`),
    KEY `idx_home_insight_expires` (`expires_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '홈 AI 인사이트 카드 캐시';
