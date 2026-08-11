package com.aewol.domain.member.service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemberProfileMapperSqlTest {

    @Test
    void profilePhoneAndPasswordSqlKeepRequiredConstraints() throws Exception {
        String mapper = resource("mapper/member/MemberMapper.xml");

        String profile = statement(mapper, "<update id=\"updateProfile\"", "</update>");
        assertFalse(profile.contains("name ="));
        assertTrue(profile.contains("phone = #{phone}"));
        assertTrue(profile.contains("profile_img = #{profileImg}"));

        String activePhone = statement(mapper, "<select id=\"existsActiveByPhone\"", "</select>");
        assertTrue(activePhone.contains("is_active = 1"));
        assertTrue(activePhone.contains("REGEXP_REPLACE"));

        String excludingSelf = statement(
                mapper, "<select id=\"existsActiveByPhoneExcludingMember\"", "</select>");
        assertTrue(excludingSelf.contains("is_active = 1"));
        assertTrue(excludingSelf.contains("member_id &lt;&gt; #{memberId}"));
        assertTrue(excludingSelf.contains("REGEXP_REPLACE"));

        String password = statement(mapper, "<update id=\"updatePassword\"", "</update>");
        assertTrue(password.contains("SET password = #{password}"));
        assertTrue(password.contains("updated_at = NOW()"));
        assertTrue(password.contains("member_id = #{memberId}"));
        assertTrue(password.contains("provider = 'LOCAL'"));
        assertTrue(password.contains("is_active = 1"));
        assertFalse(password.contains("phone ="));
        assertFalse(password.contains("name ="));
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
