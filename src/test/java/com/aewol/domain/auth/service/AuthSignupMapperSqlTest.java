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
}
