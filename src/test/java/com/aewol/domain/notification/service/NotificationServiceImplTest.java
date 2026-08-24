package com.aewol.domain.notification.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.notification.dto.NotificationListResponse;
import com.aewol.domain.notification.mapper.NotificationMapper;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceImplTest {

    private NotificationMapper mapper;
    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(NotificationMapper.class);
        service = new NotificationServiceImpl(mapper);
    }

    @Test
    void returnsOnlyAuthenticatedMembersNotificationsNewestFirstContract() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("3", null, LocalDateTime.of(2026, 8, 20, 12, 0)));
        rows.add(row("2", Timestamp.valueOf("2026-08-20 11:01:00"),
                LocalDateTime.of(2026, 8, 20, 11, 0)));
        rows.add(row("1", null, LocalDateTime.of(2026, 8, 20, 10, 0)));
        when(mapper.findByMemberId("member-1", 3, 0)).thenReturn(rows);
        when(mapper.countUnread("member-1")).thenReturn(2);

        NotificationListResponse result = service.getNotifications("member-1", 0, 2);

        assertEquals(2, result.getNotifications().size());
        assertEquals("3", result.getNotifications().get(0).getNotificationId());
        assertFalse(result.getNotifications().get(0).isRead());
        assertTrue(result.getNotifications().get(1).isRead());
        assertEquals(2, result.getUnreadCount());
        assertTrue(result.isHasNext());
        verify(mapper).findByMemberId("member-1", 3, 0);
    }

    @Test
    void rejectsPageThatWouldOverflowMapperOffset() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.getNotifications("member-1", Integer.MAX_VALUE, 100));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verify(mapper, never()).findByMemberId(any(), anyInt(), anyInt());
    }

    @Test
    void ownNotificationCanBeReadIdempotently() {
        when(mapper.existsByIdAndMemberId("10", "member-1")).thenReturn(true);

        service.markAsRead("member-1", "10");
        service.markAsRead("member-1", "10");

        verify(mapper, org.mockito.Mockito.times(2)).markAsRead("10", "member-1");
    }

    @Test
    void anotherMembersNotificationIsHiddenAsNotFound() {
        when(mapper.existsByIdAndMemberId("10", "member-1")).thenReturn(false);

        BusinessException exception = assertThrows(
                BusinessException.class, () -> service.markAsRead("member-1", "10"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        verify(mapper, never()).markAsRead(any(), any());
    }

    @Test
    void marksAllUnreadNotificationsForAuthenticatedMember() {
        service.markAllAsRead("member-1");

        verify(mapper).markAllAsRead("member-1");
    }

    @Test
    void commonCreationEntryPointStoresExtensibleTargetPath() {
        doAnswer(invocation -> {
            Map<String, Object> values = invocation.getArgument(0);
            values.put("notificationId", 42L);
            return 1;
        }).when(mapper).insert(any());

        String id = service.createNotification(
                "member-1", "GROUP_PURCHASE", "공동구매 시작", "상품을 확인해보세요.", "/group-purchase/7");

        assertEquals("42", id);
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(mapper).insert(captor.capture());
        assertEquals("member-1", captor.getValue().get("memberId"));
        assertEquals("/group-purchase/7", captor.getValue().get("targetPath"));
        assertNull(captor.getValue().get("eventKey"));
    }

    @Test
    void storesOptionalEventKeyForIdempotentInsert() {
        doAnswer(invocation -> {
            Map<String, Object> values = invocation.getArgument(0);
            values.put("notificationId", 7L);
            return 1;
        }).when(mapper).insert(any());

        String id = service.createNotification(
                "member-1", "RECURRING", "정기결제가 3일 뒤예요", "예정되어 있어요.",
                "/payment/recurring", "recurring:1:2026-08-28:RECURRING");

        assertEquals("7", id);
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(mapper).insert(captor.capture());
        assertEquals("recurring:1:2026-08-28:RECURRING", captor.getValue().get("eventKey"));
    }

    @Test
    void rejectsExternalTargetPathAndAllowsMissingTarget() {
        assertThrows(BusinessException.class, () -> service.createNotification(
                "member-1", "PAYMENT", "결제", "완료", "https://evil.example"));
        doAnswer(invocation -> {
            Map<String, Object> values = invocation.getArgument(0);
            assertNull(values.get("targetPath"));
            values.put("notificationId", 1L);
            return 1;
        }).when(mapper).insert(any());

        assertEquals("1", service.createNotification(
                "member-1", "PAYMENT", "결제", "완료", null));
    }

    private Map<String, Object> row(String id, Object readAt, LocalDateTime createdAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("notification_id", id);
        row.put("type", "PAYMENT");
        row.put("title", "제목 " + id);
        row.put("message", "내용 " + id);
        row.put("target_path", null);
        row.put("read_at", readAt);
        row.put("created_at", createdAt);
        return row;
    }
}
