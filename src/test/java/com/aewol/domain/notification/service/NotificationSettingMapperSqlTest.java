package com.aewol.domain.notification.service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationSettingMapperSqlTest {

    @Test
    void mapperProvidesMemberSelectAndNullAwarePartialUpdate() throws Exception {
        String xml = resource("mapper/notification/NotificationSettingMapper.xml");
        String select = statement(xml, "<select id=\"findByMemberId\"", "</select>");
        assertTrue(select.contains("FROM notification_setting"));
        assertTrue(select.contains("WHERE member_id = #{memberId}"));
        assertTrue(select.contains("payment_enabled"));
        assertTrue(select.contains("recurring_payment_enabled"));
        assertTrue(select.contains("family_share_enabled"));
        assertTrue(select.contains("community_enabled"));
        assertTrue(select.contains("marketing_enabled"));

        String update = statement(xml, "<update id=\"updatePartial\"", "</update>");
        assertTrue(update.contains("<set>"));
        assertBinding(update, "paymentEnabled", "payment_enabled");
        assertBinding(update, "recurringPaymentEnabled", "recurring_payment_enabled");
        assertBinding(update, "familyShareEnabled", "family_share_enabled");
        assertBinding(update, "communityEnabled", "community_enabled");
        assertBinding(update, "marketingEnabled", "marketing_enabled");
        assertTrue(update.contains("WHERE member_id = #{memberId}"));
    }

    private void assertBinding(String update, String property, String column) {
        assertTrue(update.contains("<if test=\"" + property + " != null\">"));
        assertTrue(update.contains(column + " = #{" + property + "}"));
        assertTrue(!update.contains("<if test=\"" + property + "\">"));
    }

    private String resource(String path) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String statement(String xml, String startMarker, String endMarker) {
        int start = xml.indexOf(startMarker);
        assertTrue(start >= 0);
        int end = xml.indexOf(endMarker, start);
        assertTrue(end > start);
        return xml.substring(start, end + endMarker.length());
    }
}
