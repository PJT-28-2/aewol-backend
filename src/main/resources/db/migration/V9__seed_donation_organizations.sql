-- Donation organization catalog used by the social-impact feature.
--
-- category is normalized to the frontend filter value. The more specific
-- classification supplied for each organization is retained in activity_tags.
-- Guard every insert by name so databases with manually registered rows do not
-- receive duplicate organizations when this migration is introduced.

INSERT INTO `donation_organization`
    (`name`, `description`, `homepage_url`, `category`, `target_species`,
     `activity_tags`, `is_active`, `display_order`)
SELECT
    '동물권행동 카라 (KARA)',
    '유기동물 구조·입양(더불어숨센터), 학대 제보 대응, 법·제도 개선',
    'https://www.ekara.org/',
    '유기동물',
    'ALL',
    JSON_ARRAY('종합 동물권', '구조', '입양', '학대 대응', '법·제도 개선'),
    1,
    1
WHERE NOT EXISTS (
    SELECT 1 FROM `donation_organization` WHERE `name` = '동물권행동 카라 (KARA)'
);

INSERT INTO `donation_organization`
    (`name`, `description`, `homepage_url`, `category`, `target_species`,
     `activity_tags`, `is_active`, `display_order`)
SELECT
    '동물자유연대',
    '피학대 및 유기동물 구조, 온센터(보호소) 운영, 동물복지 캠페인',
    'https://www.animals.or.kr/',
    '유기동물',
    'ALL',
    JSON_ARRAY('종합 동물권', '구조', '보호소', '입양', '동물복지'),
    1,
    2
WHERE NOT EXISTS (
    SELECT 1 FROM `donation_organization` WHERE `name` = '동물자유연대'
);

INSERT INTO `donation_organization`
    (`name`, `description`, `homepage_url`, `category`, `target_species`,
     `activity_tags`, `is_active`, `display_order`)
SELECT
    '비글구조네트워크',
    '실험용 비글 구조, 번식장·식용농장 구조, 사설보호소 환경 개선',
    'http://www.bnetwork.or.kr/',
    '유기동물',
    'DOG',
    JSON_ARRAY('실험동물', '유기견', '비글', '번식장 구조', '사설보호소'),
    1,
    3
WHERE NOT EXISTS (
    SELECT 1 FROM `donation_organization` WHERE `name` = '비글구조네트워크'
);

INSERT INTO `donation_organization`
    (`name`, `description`, `homepage_url`, `category`, `target_species`,
     `activity_tags`, `is_active`, `display_order`)
SELECT
    '한국고양이보호협회',
    '길고양이 TNR(중성화) 지원, 긴급 치료비 지원, 구조 및 임시보호',
    'https://www.gochabo.org/',
    '유기동물',
    'CAT',
    JSON_ARRAY('길고양이', '고양이', 'TNR', '치료비 지원', '임시보호'),
    1,
    4
WHERE NOT EXISTS (
    SELECT 1 FROM `donation_organization` WHERE `name` = '한국고양이보호협회'
);

INSERT INTO `donation_organization`
    (`name`, `description`, `homepage_url`, `category`, `target_species`,
     `activity_tags`, `is_active`, `display_order`)
SELECT
    '사단법인 나비야사랑해',
    '유기·학대 고양이 전문 구조 및 보호소 운영, 입양 캠페인',
    'http://www.nabiya.org/',
    '유기동물',
    'CAT',
    JSON_ARRAY('고양이 전문', '구조', '보호소', '입양', '학대 대응'),
    1,
    5
WHERE NOT EXISTS (
    SELECT 1 FROM `donation_organization` WHERE `name` = '사단법인 나비야사랑해'
);

INSERT INTO `donation_organization`
    (`name`, `description`, `homepage_url`, `category`, `target_species`,
     `activity_tags`, `is_active`, `display_order`)
SELECT
    '동물권단체 케어 (CARE)',
    '위기 동물 긴급 구조, 피학대 동물 보호, 입양 센터 운영',
    'https://fromcare.org/',
    '유기동물',
    'ALL',
    JSON_ARRAY('구조', '보호', '긴급 구조', '학대 대응', '입양 센터'),
    1,
    6
WHERE NOT EXISTS (
    SELECT 1 FROM `donation_organization` WHERE `name` = '동물권단체 케어 (CARE)'
);

INSERT INTO `donation_organization`
    (`name`, `description`, `homepage_url`, `category`, `target_species`,
     `activity_tags`, `is_active`, `display_order`)
SELECT
    '동물보호단체 라이프 (LIFE)',
    '불법 번식장·도살장 구조, 시골개 1m의 삶 개선, 입양 연계',
    'https://lifewithdogs.or.kr/',
    '유기동물',
    'DOG',
    JSON_ARRAY('구조', '입양', '번식장 구조', '도살장 구조', '시골개'),
    1,
    7
WHERE NOT EXISTS (
    SELECT 1 FROM `donation_organization` WHERE `name` = '동물보호단체 라이프 (LIFE)'
);

INSERT INTO `donation_organization`
    (`name`, `description`, `homepage_url`, `category`, `target_species`,
     `activity_tags`, `is_active`, `display_order`)
SELECT
    '팅커벨프로젝트',
    '지자체 보호소 안락사 위기 동물 구조, 임시보호 및 입양',
    'http://www.tinkerbellproject.co.kr/',
    '유기동물',
    'ALL',
    JSON_ARRAY('유기동물 구조', '안락사 위기', '임시보호', '입양'),
    1,
    8
WHERE NOT EXISTS (
    SELECT 1 FROM `donation_organization` WHERE `name` = '팅커벨프로젝트'
);

INSERT INTO `donation_organization`
    (`name`, `description`, `homepage_url`, `category`, `target_species`,
     `activity_tags`, `is_active`, `display_order`)
SELECT
    '행동하는 동물성 (행동사)',
    '안락사 위기 유기동물 구조, 쉼터 및 입양뜰 운영',
    'https://blog.naver.com/hdss_p',
    '유기동물',
    'ALL',
    JSON_ARRAY('입양', '보호', '안락사 위기', '쉼터', '입양뜰'),
    1,
    9
WHERE NOT EXISTS (
    SELECT 1 FROM `donation_organization` WHERE `name` = '행동하는 동물성 (행동사)'
);

INSERT INTO `donation_organization`
    (`name`, `description`, `homepage_url`, `category`, `target_species`,
     `activity_tags`, `is_active`, `display_order`)
SELECT
    '코리안독스 (KDS)',
    '식용견농장 구조, 대형견 및 진돗개 계열 해외입양 연계',
    'http://koreandogs.or.kr/',
    '유기동물',
    'DOG',
    JSON_ARRAY('구조', '해외입양', '식용견농장', '대형견', '진돗개'),
    1,
    10
WHERE NOT EXISTS (
    SELECT 1 FROM `donation_organization` WHERE `name` = '코리안독스 (KDS)'
);

INSERT INTO `donation_organization`
    (`name`, `description`, `homepage_url`, `category`, `target_species`,
     `activity_tags`, `is_active`, `display_order`)
SELECT
    '동물구조119',
    '재난·위기 현장 동물 긴급 구조, 방치견 구조, 보호소 봉사 지원',
    'http://119.or.kr/',
    '유기동물',
    'ALL',
    JSON_ARRAY('긴급구조', '재난 구조', '방치견', '보호소 지원'),
    1,
    11
WHERE NOT EXISTS (
    SELECT 1 FROM `donation_organization` WHERE `name` = '동물구조119'
);

INSERT INTO `donation_organization`
    (`name`, `description`, `homepage_url`, `category`, `target_species`,
     `activity_tags`, `is_active`, `display_order`)
SELECT
    '유기동물 행복찾기 (유행사)',
    '길거리 유기동물 입양 행사 개최, 구조 및 입양 연결',
    'http://yuhaengsa.uconne.com/',
    '유기동물',
    'ALL',
    JSON_ARRAY('입양 캠페인', '유기동물', '구조', '입양 행사'),
    1,
    12
WHERE NOT EXISTS (
    SELECT 1 FROM `donation_organization` WHERE `name` = '유기동물 행복찾기 (유행사)'
);

INSERT INTO `donation_organization`
    (`name`, `description`, `homepage_url`, `category`, `target_species`,
     `activity_tags`, `is_active`, `display_order`)
SELECT
    '캣치독팀 (CATCHDOG TEAM)',
    '불법 번식장·투견장 등 현장 적발 및 고발, 위기 동물 구조',
    'http://catchdog.or.kr/',
    '유기동물',
    'ALL',
    JSON_ARRAY('학대 제보', '구조', '불법 번식장', '투견장', '고발'),
    1,
    13
WHERE NOT EXISTS (
    SELECT 1 FROM `donation_organization` WHERE `name` = '캣치독팀 (CATCHDOG TEAM)'
);

INSERT INTO `donation_organization`
    (`name`, `description`, `homepage_url`, `category`, `target_species`,
     `activity_tags`, `is_active`, `display_order`)
SELECT
    '사단법인 도로시지키미',
    '안락사 대상 유기견 구조, 임시보호 및 입양 추진',
    'https://www.instagram.com/dorothy_rescue_/',
    '유기동물',
    'DOG',
    JSON_ARRAY('유기견 구조', '안락사 대상', '임시보호', '입양'),
    1,
    14
WHERE NOT EXISTS (
    SELECT 1 FROM `donation_organization` WHERE `name` = '사단법인 도로시지키미'
);

INSERT INTO `donation_organization`
    (`name`, `description`, `homepage_url`, `category`, `target_species`,
     `activity_tags`, `is_active`, `display_order`)
SELECT
    '비글구조네트워크 보듬이',
    '환경이 열악한 전국 사설 유기동물 보호소 사료·의료 지원',
    'http://www.bnetwork.or.kr/',
    '유기동물',
    'ALL',
    JSON_ARRAY('사설보호소 지원', '사료 지원', '의료 지원', '유기동물'),
    1,
    15
WHERE NOT EXISTS (
    SELECT 1 FROM `donation_organization` WHERE `name` = '비글구조네트워크 보듬이'
);
