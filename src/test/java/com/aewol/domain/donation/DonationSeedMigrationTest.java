package com.aewol.domain.donation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DonationSeedMigrationTest {

    private static final List<String> ORGANIZATIONS = List.of(
            "동물권행동 카라 (KARA)",
            "동물자유연대",
            "비글구조네트워크",
            "한국고양이보호협회",
            "사단법인 나비야사랑해",
            "동물권단체 케어 (CARE)",
            "동물보호단체 라이프 (LIFE)",
            "팅커벨프로젝트",
            "행동하는 동물성 (행동사)",
            "코리안독스 (KDS)",
            "동물구조119",
            "유기동물 행복찾기 (유행사)",
            "캣치독팀 (CATCHDOG TEAM)",
            "사단법인 도로시지키미",
            "비글구조네트워크 보듬이"
    );

    private static final List<String> DEMO_CAMPAIGNS = List.of(
            "[시연] 유기동물 구조·입양 활동 지원",
            "[시연] 피학대 동물 구조·보호 지원",
            "[시연] 실험 비글 구조와 보호소 개선",
            "[시연] 길고양이 TNR·긴급 치료 지원",
            "[시연] 유기·학대 고양이 구조 지원",
            "[시연] 위기·피학대 동물 긴급 구조",
            "[시연] 불법 번식장 구조와 입양 지원",
            "[시연] 안락사 위기 동물 구조 지원",
            "[시연] 입양뜰 쉼터 운영 지원",
            "[시연] 식용견농장 구조·해외입양 지원",
            "[시연] 재난·위기 동물 긴급 구조 지원",
            "[시연] 유기동물 입양 캠페인 지원",
            "[시연] 불법 번식장·투견장 구조 지원",
            "[시연] 안락사 대상 유기견 구조 지원",
            "[시연] 사설보호소 사료·의료 지원"
    );

    @Test
    @DisplayName("V9은 기부처 15곳을 중복 방지 조건과 함께 포함한다")
    void should_includeOrganizations_when_v9MigrationIsLoaded() throws IOException {
        String migration = readMigration("V9__seed_donation_organizations.sql");

        assertEquals(15, countOccurrences(migration, "INSERT INTO `donation_organization`"));
        assertEquals(15, countOccurrences(migration, "WHERE NOT EXISTS"));
        ORGANIZATIONS.forEach(name -> assertTrue(migration.contains("'" + name + "'"), name));
    }

    @Test
    @DisplayName("V10은 각 기부처에 시연용 캠페인 한 건을 연결한다")
    void should_includeDemoCampaigns_when_v10MigrationIsLoaded() throws IOException {
        String migration = readMigration("V10__seed_demo_donation_campaigns.sql");

        ORGANIZATIONS.forEach(name -> assertTrue(migration.contains("'" + name + "'"), name));
        DEMO_CAMPAIGNS.forEach(title -> assertTrue(migration.contains("'" + title + "'"), title));
        assertTrue(migration.contains("DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 180 DAY)"));
        assertTrue(migration.contains("WHERE NOT EXISTS"));
    }

    private String readMigration(String filename) throws IOException {
        String resourcePath = "db/migration/" + filename;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(input, resourcePath);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private int countOccurrences(String value, String token) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
