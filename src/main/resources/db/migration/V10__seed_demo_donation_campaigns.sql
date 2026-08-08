-- Demo campaigns for the donation organizations seeded by V9.
--
-- Amounts, participant counts, and campaign dates below are fictional values
-- used only to demonstrate progress and D-day UI. They are not official
-- fundraising figures from the organizations. The [시연] prefix keeps that
-- distinction visible in the application.

INSERT INTO `donation_campaign`
    (`organization_id`, `channel_id`, `title`, `category`, `description`,
     `image_url`, `target_amount`, `raised_amount`, `participant_count`,
     `starts_at`, `ends_at`, `is_recommended`, `is_active`, `display_order`)
SELECT
    organization.`organization_id`,
    NULL,
    seed.`title`,
    '유기동물',
    seed.`description`,
    NULL,
    seed.`target_amount`,
    seed.`raised_amount`,
    seed.`participant_count`,
    CURRENT_TIMESTAMP - INTERVAL '30' DAY,
    CURRENT_TIMESTAMP + INTERVAL '180' DAY,
    seed.`is_recommended`,
    1,
    seed.`display_order`
FROM `donation_organization` organization
JOIN (
    SELECT
        '동물권행동 카라 (KARA)' AS `organization_name`,
        '[시연] 유기동물 구조·입양 활동 지원' AS `title`,
        '구조된 유기동물의 치료와 보호, 새로운 가족을 찾는 입양 활동을 지원합니다.' AS `description`,
        30000000.00 AS `target_amount`, 12600000.00 AS `raised_amount`,
        421 AS `participant_count`, 1 AS `is_recommended`, 1 AS `display_order`
    UNION ALL SELECT
        '동물자유연대',
        '[시연] 피학대 동물 구조·보호 지원',
        '피학대·유기동물 구조와 보호소 운영, 입양 연계 활동을 지원합니다.',
        25000000.00, 9850000.00, 356, 1, 2
    UNION ALL SELECT
        '비글구조네트워크',
        '[시연] 실험 비글 구조와 보호소 개선',
        '실험동물과 번식장 동물을 구조하고 사설보호소 환경을 개선하는 활동을 지원합니다.',
        20000000.00, 7200000.00, 215, 1, 3
    UNION ALL SELECT
        '한국고양이보호협회',
        '[시연] 길고양이 TNR·긴급 치료 지원',
        '길고양이 중성화와 사고·질병으로 치료가 필요한 고양이의 의료 활동을 지원합니다.',
        15000000.00, 6450000.00, 294, 0, 4
    UNION ALL SELECT
        '사단법인 나비야사랑해',
        '[시연] 유기·학대 고양이 구조 지원',
        '유기되거나 학대받은 고양이의 구조, 보호소 생활과 입양 연계를 지원합니다.',
        12000000.00, 4320000.00, 188, 0, 5
    UNION ALL SELECT
        '동물권단체 케어 (CARE)',
        '[시연] 위기·피학대 동물 긴급 구조',
        '학대와 위기 상황에 놓인 동물의 긴급 구조와 치료, 보호 활동을 지원합니다.',
        20000000.00, 8800000.00, 267, 0, 6
    UNION ALL SELECT
        '동물보호단체 라이프 (LIFE)',
        '[시연] 불법 번식장 구조와 입양 지원',
        '불법 번식장과 도살장 동물을 구조하고 안전한 입양처를 찾는 활동을 지원합니다.',
        18000000.00, 5760000.00, 179, 0, 7
    UNION ALL SELECT
        '팅커벨프로젝트',
        '[시연] 안락사 위기 동물 구조 지원',
        '보호소에서 안락사 위기에 놓인 동물을 구조해 임시보호와 입양을 연결합니다.',
        15000000.00, 5100000.00, 204, 0, 8
    UNION ALL SELECT
        '행동하는 동물성 (행동사)',
        '[시연] 입양뜰 쉼터 운영 지원',
        '구조된 유기동물이 안전하게 머물며 가족을 기다릴 수 있도록 쉼터 운영을 지원합니다.',
        12000000.00, 3840000.00, 151, 0, 9
    UNION ALL SELECT
        '코리안독스 (KDS)',
        '[시연] 식용견농장 구조·해외입양 지원',
        '식용견농장에서 구조한 대형견과 진돗개 계열 동물의 치료와 해외입양을 지원합니다.',
        20000000.00, 6600000.00, 132, 0, 10
    UNION ALL SELECT
        '동물구조119',
        '[시연] 재난·위기 동물 긴급 구조 지원',
        '재난과 방치 현장에서 위기에 놓인 동물을 구조하고 보호소 지원을 이어갑니다.',
        15000000.00, 7050000.00, 238, 0, 11
    UNION ALL SELECT
        '유기동물 행복찾기 (유행사)',
        '[시연] 유기동물 입양 캠페인 지원',
        '길거리 유기동물을 구조하고 입양 행사를 통해 새로운 가족과 연결합니다.',
        10000000.00, 2800000.00, 97, 0, 12
    UNION ALL SELECT
        '캣치독팀 (CATCHDOG TEAM)',
        '[시연] 불법 번식장·투견장 구조 지원',
        '불법 번식장과 투견장 현장을 적발하고 구조된 동물의 치료와 보호를 지원합니다.',
        18000000.00, 8100000.00, 276, 0, 13
    UNION ALL SELECT
        '사단법인 도로시지키미',
        '[시연] 안락사 대상 유기견 구조 지원',
        '안락사 대상 유기견을 구조하고 임시보호와 입양을 진행하는 활동을 지원합니다.',
        12000000.00, 3360000.00, 119, 0, 14
    UNION ALL SELECT
        '비글구조네트워크 보듬이',
        '[시연] 사설보호소 사료·의료 지원',
        '환경이 열악한 사설 유기동물 보호소에 필요한 사료와 의료 지원을 제공합니다.',
        20000000.00, 9400000.00, 311, 0, 15
) seed ON seed.`organization_name` = organization.`name`
WHERE NOT EXISTS (
    SELECT 1
    FROM `donation_campaign` existing
    WHERE existing.`organization_id` = organization.`organization_id`
      AND existing.`title` = seed.`title`
);
