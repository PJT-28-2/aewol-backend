package com.aewol.domain.auth.service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthSignupMapperSqlTest {

    @Test
    void mapperSqlUsesDbClockInclusiveBoundaryAndMarketingOnlyUpsert() throws Exception {
        String memberSql = resource("mapper/member/MemberMapper.xml");
        assertTrue(memberSql.contains("withdrawn_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)"));
        assertTrue(memberSql.contains("ORDER BY withdrawn_at DESC, member_id DESC"));
        assertTrue(memberSql.contains("LIMIT 1"));
        assertTrue(memberSql.contains("FOR UPDATE"));
        String deactivateSql = statement(memberSql, "<update id=\"deactivateActiveMember\"", "</update>");
        assertTrue(deactivateSql.contains("SET is_active = 0"));
        assertTrue(deactivateSql.contains("withdrawn_at = NOW()"));
        assertTrue(deactivateSql.contains("WHERE member_id = #{memberId}"));
        assertTrue(deactivateSql.contains("AND is_active = 1"));
        assertTrue(!deactivateSql.contains("DELETE"));
        assertTrue(!deactivateSql.contains("wallet"));

        String activeKakaoSql = statement(
                memberSql, "<select id=\"findActiveKakaoByIdentity\"", "</select>");
        String inactiveKakaoSql = statement(
                memberSql, "<select id=\"existsInactiveByKakaoIdentity\"", "</select>");
        assertTrue(activeKakaoSql.contains("TRIM(#{email}) &lt;&gt; ''"));
        assertTrue(inactiveKakaoSql.contains("TRIM(#{email}) &lt;&gt; ''"));
        // 비활성 LOCAL 이메일도 KAKAO 신규 생성을 막아 30일 복구 권리를 보존한다.
        assertTrue(!inactiveKakaoSql.contains("WHERE provider = 'KAKAO'"));
        assertTrue(!inactiveKakaoSql.contains("AND provider = 'KAKAO'"));

        String notificationSql = resource("mapper/notification/NotificationSettingMapper.xml");
        String duplicateClause = notificationSql.substring(notificationSql.indexOf("ON DUPLICATE KEY UPDATE"));
        assertTrue(notificationSql.contains("#{memberId}, TRUE, TRUE, TRUE, TRUE, #{marketingEnabled}"));
        assertTrue(duplicateClause.contains("marketing_enabled = #{marketingEnabled}"));
        assertTrue(!duplicateClause.contains("payment_enabled ="));
        assertTrue(!duplicateClause.contains("recurring_payment_enabled ="));
        assertTrue(!duplicateClause.contains("family_share_enabled ="));
        assertTrue(!duplicateClause.contains("community_enabled ="));
    }

    private String resource(String path) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String statement(String sql, String startMarker, String endMarker) {
        int start = sql.indexOf(startMarker);
        assertTrue(start >= 0);
        int end = sql.indexOf(endMarker, start);
        assertTrue(end > start);
        return sql.substring(start, end + endMarker.length());
    }
}
