package com.aewol.domain.auth.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AccountFindMapperSqlTest {

    @Test
    void accountFindQueryUsesMinimalActiveNameAndNormalizedPhoneProjectionWithoutLimit() throws Exception {
        String sql = mapperSection("findActiveForAccountFind");
        assertTrue(sql.contains("SELECT member_id, provider, email"));
        assertTrue(sql.contains("name = #{name}"));
        assertTrue(sql.contains("is_active = 1"));
        assertTrue(sql.contains("REGEXP_REPLACE"));
        assertTrue(sql.contains("= #{phone}"));
        assertFalse(sql.toUpperCase().contains("LIMIT"));
        assertFalse(sql.contains("password"));
        assertFalse(sql.contains("address"));
    }

    private String mapperSection(String id) throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("mapper/member/MemberMapper.xml")) {
            assertNotNull(input);
            String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            int start = xml.indexOf("id=\"" + id + "\"");
            int end = xml.indexOf("</select>", start);
            assertTrue(start >= 0);
            assertTrue(end > start);
            return xml.substring(start, end);
        }
    }
}
