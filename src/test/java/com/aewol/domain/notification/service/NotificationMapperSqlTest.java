package com.aewol.domain.notification.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationMapperSqlTest {

    private final String xml = readMapper();

    @Test
    void insertStoresOptionalEventKey() {
        String sql = statement("<insert id=\"insert\"", "</insert>");
        assertTrue(sql.contains("event_key"));
        assertTrue(sql.contains("#{eventKey}"));
    }

    @Test
    void listIsScopedToMemberAndOrderedNewestFirst() {
        String sql = statement("<select id=\"findByMemberId\"", "</select>");
        assertTrue(sql.contains("WHERE member_id = #{memberId}"));
        assertTrue(sql.contains("ORDER BY created_at DESC, notification_id DESC"));
        assertTrue(sql.contains("LIMIT #{limit} OFFSET #{offset}"));
    }

    @Test
    void singleReadUpdateCannotTouchAnotherMembersNotification() {
        String sql = statement("<update id=\"markAsRead\"", "</update>");
        assertTrue(sql.contains("notification_id = #{notificationId}"));
        assertTrue(sql.contains("member_id = #{memberId}"));
        assertTrue(sql.contains("COALESCE(read_at, NOW(6))"));
    }

    @Test
    void unreadQueriesAreAlwaysScopedToAuthenticatedMember() {
        String count = statement("<select id=\"countUnread\"", "</select>");
        String readAll = statement("<update id=\"markAllAsRead\"", "</update>");
        assertTrue(count.contains("member_id = #{memberId}"));
        assertTrue(count.contains("read_at IS NULL"));
        assertTrue(readAll.contains("member_id = #{memberId}"));
        assertTrue(readAll.contains("read_at IS NULL"));
    }

    private String statement(String start, String end) {
        int from = xml.indexOf(start);
        int to = xml.indexOf(end, from);
        return xml.substring(from, to + end.length()).replaceAll("\\s+", " ");
    }

    private String readMapper() {
        try {
            return Files.readString(
                    Path.of("src/main/resources/mapper/notification/NotificationMapper.xml"),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
