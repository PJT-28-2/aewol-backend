package com.aewol.domain.donation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DonationSeedMigrationTest {

    private static final Map<String, String> EXPECTED_CAMPAIGNS = expectedCampaigns();

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:donation_seed_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        jdbcTemplate = new JdbcTemplate(dataSource);

        createDonationTables();

        Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("8"))
                .target(MigrationVersion.fromVersion("10"))
                .load()
                .migrate();
    }

    @Test
    @DisplayName("V9은 자동 생성 PK로 기부처 15곳을 등록한다")
    void should_insertOrganizations_when_v9MigrationRuns() {
        Integer organizationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM donation_organization", Integer.class);
        Integer generatedIdCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(organization_id) FROM donation_organization", Integer.class);

        assertEquals(15, organizationCount);
        assertEquals(15, generatedIdCount);
    }

    @Test
    @DisplayName("V10은 기부처별 시연용 캠페인을 정확히 연결한다")
    void should_connectDemoCampaigns_when_v10MigrationRuns() {
        Map<String, String> campaigns = jdbcTemplate.query(
                "SELECT o.name, c.title "
                        + "FROM donation_organization o "
                        + "JOIN donation_campaign c ON c.organization_id = o.organization_id "
                        + "ORDER BY c.display_order",
                resultSet -> {
                    Map<String, String> rows = new LinkedHashMap<>();
                    while (resultSet.next()) {
                        rows.put(resultSet.getString("name"), resultSet.getString("title"));
                    }
                    return rows;
                });
        Integer generatedIdCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(campaign_id) FROM donation_campaign", Integer.class);

        assertEquals(15, generatedIdCount);
        assertEquals(EXPECTED_CAMPAIGNS, campaigns);
    }

    private void createDonationTables() {
        jdbcTemplate.execute("""
                CREATE TABLE donation_organization (
                    organization_id BIGINT NOT NULL AUTO_INCREMENT,
                    name VARCHAR(100) NOT NULL,
                    description TEXT NULL,
                    logo_url VARCHAR(500) NULL,
                    homepage_url VARCHAR(500) NULL,
                    region VARCHAR(100) NULL,
                    category VARCHAR(30) NULL,
                    target_species VARCHAR(20) NOT NULL DEFAULT 'ALL',
                    activity_tags JSON NULL,
                    total_donation_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
                    is_active TINYINT(1) NOT NULL DEFAULT 1,
                    display_order INT NOT NULL DEFAULT 0,
                    verified_at TIMESTAMP NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (organization_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE donation_campaign (
                    campaign_id BIGINT NOT NULL AUTO_INCREMENT,
                    organization_id BIGINT NOT NULL,
                    channel_id BIGINT NULL,
                    title VARCHAR(200) NOT NULL,
                    category VARCHAR(30) NULL,
                    description TEXT NULL,
                    image_url VARCHAR(500) NULL,
                    target_amount DECIMAL(15,2) NULL,
                    raised_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
                    participant_count INT NOT NULL DEFAULT 0,
                    starts_at TIMESTAMP NULL,
                    ends_at TIMESTAMP NULL,
                    is_recommended TINYINT(1) NOT NULL DEFAULT 0,
                    is_active TINYINT(1) NOT NULL DEFAULT 1,
                    display_order INT NOT NULL DEFAULT 0,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (campaign_id),
                    CONSTRAINT fk_demo_campaign_organization
                        FOREIGN KEY (organization_id)
                        REFERENCES donation_organization (organization_id)
                )
                """);
    }

    private static Map<String, String> expectedCampaigns() {
        Map<String, String> campaigns = new LinkedHashMap<>();
        campaigns.put("동물권행동 카라 (KARA)", "[시연] 유기동물 구조·입양 활동 지원");
        campaigns.put("동물자유연대", "[시연] 피학대 동물 구조·보호 지원");
        campaigns.put("비글구조네트워크", "[시연] 실험 비글 구조와 보호소 개선");
        campaigns.put("한국고양이보호협회", "[시연] 길고양이 TNR·긴급 치료 지원");
        campaigns.put("사단법인 나비야사랑해", "[시연] 유기·학대 고양이 구조 지원");
        campaigns.put("동물권단체 케어 (CARE)", "[시연] 위기·피학대 동물 긴급 구조");
        campaigns.put("동물보호단체 라이프 (LIFE)", "[시연] 불법 번식장 구조와 입양 지원");
        campaigns.put("팅커벨프로젝트", "[시연] 안락사 위기 동물 구조 지원");
        campaigns.put("행동하는 동물성 (행동사)", "[시연] 입양뜰 쉼터 운영 지원");
        campaigns.put("코리안독스 (KDS)", "[시연] 식용견농장 구조·해외입양 지원");
        campaigns.put("동물구조119", "[시연] 재난·위기 동물 긴급 구조 지원");
        campaigns.put("유기동물 행복찾기 (유행사)", "[시연] 유기동물 입양 캠페인 지원");
        campaigns.put("캣치독팀 (CATCHDOG TEAM)", "[시연] 불법 번식장·투견장 구조 지원");
        campaigns.put("사단법인 도로시지키미", "[시연] 안락사 대상 유기견 구조 지원");
        campaigns.put("비글구조네트워크 보듬이", "[시연] 사설보호소 사료·의료 지원");
        return campaigns;
    }
}
