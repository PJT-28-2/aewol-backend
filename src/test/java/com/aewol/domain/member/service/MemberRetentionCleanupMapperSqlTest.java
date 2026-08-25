package com.aewol.domain.member.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MemberRetentionCleanupMapperSqlTest {

    @Test
    void cleanupUsesExclusiveThirtyDayBoundaryLockAndConditionalFinalUpdate() throws Exception {
        String sql = resource("mapper/member/MemberMapper.xml");
        String candidates = statement(sql,
                "<select id=\"findRetentionCleanupCandidateIds\"", "</select>");
        String locked = statement(sql,
                "<select id=\"findRetentionCleanupTargetForUpdate\"", "</select>");
        String purge = statement(sql, "<update id=\"purgeMemberIdentity\"", "</update>");

        for (String retentionStatement : new String[]{candidates, locked, purge}) {
            assertTrue(retentionStatement.contains(
                    "withdrawn_at &lt; DATE_SUB(NOW(), INTERVAL 30 DAY)"));
            assertFalse(retentionStatement.contains("withdrawn_at &lt;="));
            assertTrue(retentionStatement.contains("is_active = 0"));
            assertTrue(retentionStatement.contains("purged_at IS NULL"));
        }
        assertTrue(locked.contains("FOR UPDATE"));
        assertTrue(purge.contains("purged_at = NOW()"));
    }

    @Test
    void purgeRemovesIdentityAndPiiWithoutDeletingMemberOrFinancialHistory() throws Exception {
        String sql = resource("mapper/member/MemberMapper.xml");
        String purge = statement(sql, "<update id=\"purgeMemberIdentity\"", "</update>");

        for (String assignment : new String[]{
                "email = NULL", "password = NULL", "simple_password = NULL", "name = NULL",
                "phone = NULL", "profile_img = NULL", "provider_id = NULL", "zip_code = NULL",
                "address = NULL", "address_detail = NULL", "email_verified = 'N'"}) {
            assertTrue(purge.contains(assignment), assignment);
        }
        assertFalse(purge.contains("is_active = 1"));
        assertFalse(sql.contains("DELETE FROM member "));
        for (String preservedTable : new String[]{
                "wallet", "transaction", "toss_charge_order", "wallet_withdrawal_request",
                "donation_history", "donation_roundup", "recurring_payment",
                "group_purchase_participant", "insurance_claim"}) {
            assertFalse(sql.contains("DELETE FROM " + preservedTable), preservedTable);
        }
    }

    @Test
    void cleanupAnonymizesLinkedAccountAndDeletesOnlyAccountScopedSettingsAndCaches() throws Exception {
        String sql = resource("mapper/member/MemberMapper.xml");
        String accounts = statement(sql, "<update id=\"anonymizeLinkedAccounts\"", "</update>");

        assertTrue(accounts.contains("account_number = 'PURGED'"));
        assertTrue(accounts.contains("account_number_hash = SHA2"));
        assertTrue(accounts.contains("status = 'INACTIVE'"));
        assertTrue(accounts.contains("is_primary = 0"));
        assertFalse(accounts.contains("DELETE"));

        for (String cleanup : new String[]{
                "account_verification", "notification", "notification_setting", "donation_setting",
                "member_donation_preference", "support_program_interest", "home_insight"}) {
            assertTrue(sql.contains("DELETE FROM " + cleanup + " WHERE member_id = #{memberId}"), cleanup);
        }
    }

    @Test
    void recoveryLookupsIgnorePurgedTombstones() throws Exception {
        String sql = resource("mapper/member/MemberMapper.xml");
        String local = statement(sql,
                "<select id=\"findLatestInactiveByEmailForUpdate\"", "</select>");
        String kakao = statement(sql,
                "<select id=\"findInactiveKakaoByProviderIdForUpdate\"", "</select>");
        String kakaoExists = statement(sql,
                "<select id=\"existsInactiveKakaoByProviderId\"", "</select>");

        assertTrue(local.contains("purged_at IS NULL"));
        assertTrue(kakao.contains("purged_at IS NULL"));
        assertTrue(kakaoExists.contains("purged_at IS NULL"));
        assertTrue(local.contains("withdrawn_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)"));
        assertTrue(kakao.contains("withdrawn_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)"));
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
