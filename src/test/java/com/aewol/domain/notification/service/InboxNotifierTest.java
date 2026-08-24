package com.aewol.domain.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aewol.domain.notification.mapper.NotificationSettingMapper;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

@ExtendWith(MockitoExtension.class)
class InboxNotifierTest {

    @Mock InboxNotificationWriter inboxNotificationWriter;
    @Mock NotificationSettingMapper notificationSettingMapper;

    private InboxNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new InboxNotifier(inboxNotificationWriter, notificationSettingMapper);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void should_createNotification_whenPaymentSettingIsEnabled() {
        when(notificationSettingMapper.findByMemberId("member-1"))
                .thenReturn(Map.of("payment_enabled", 1));
        when(inboxNotificationWriter.write(any(), any(), any(), any(), any(), any()))
                .thenReturn("42");

        InboxNotifier.Result result = notifier.notifyQuietly(
                "member-1", InboxNotifier.Channel.PAYMENT,
                "PAYMENT", "결제가 완료됐어요", "가게에서 1000원이 결제됐어요.", "/wallet/history");

        assertEquals(InboxNotifier.Result.CREATED, result);
        verify(inboxNotificationWriter).write(
                "member-1", "PAYMENT", "결제가 완료됐어요",
                "가게에서 1000원이 결제됐어요.", "/wallet/history", null);
    }

    @Test
    void should_skipNotification_whenChannelSettingIsOff() {
        when(notificationSettingMapper.findByMemberId("member-1"))
                .thenReturn(Map.of("payment_enabled", 0, "recurring_payment_enabled", 1));

        InboxNotifier.Result result = notifier.notifyQuietly(
                "member-1", InboxNotifier.Channel.PAYMENT,
                "PAYMENT", "결제가 완료됐어요", "내용", "/wallet/history");

        assertEquals(InboxNotifier.Result.DISABLED, result);
        verifyNoInteractions(inboxNotificationWriter);
    }

    @Test
    void should_skipNotification_whenSettingsRowIsMissing() {
        when(notificationSettingMapper.findByMemberId("member-1")).thenReturn(null);

        InboxNotifier.Result result = notifier.notifyQuietly(
                "member-1", InboxNotifier.Channel.FAMILY,
                "FAMILY_SHARE", "제목", "내용", "/share");

        assertEquals(InboxNotifier.Result.DISABLED, result);
        verifyNoInteractions(inboxNotificationWriter);
    }

    @Test
    void should_returnFailed_whenCreateNotificationFails() {
        when(notificationSettingMapper.findByMemberId("member-1"))
                .thenReturn(Map.of("community_enabled", true));
        when(inboxNotificationWriter.write(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("저장 실패"));

        InboxNotifier.Result result = notifier.notifyQuietly(
                "member-1", InboxNotifier.Channel.COMMUNITY,
                "GROUP_PURCHASE", "제목", "내용", "/group-purchase/1");

        assertEquals(InboxNotifier.Result.FAILED, result);
        verify(inboxNotificationWriter).write(any(), any(), any(), any(), any(), any());
    }

    @Test
    void should_returnDuplicate_whenEventKeyAlreadyExists() {
        when(notificationSettingMapper.findByMemberId("member-1"))
                .thenReturn(Map.of("recurring_payment_enabled", 1));
        when(inboxNotificationWriter.write(any(), any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("uk_notification_event_key"));

        InboxNotifier.Result result = notifier.notifyQuietly(
                "member-1", InboxNotifier.Channel.RECURRING,
                "RECURRING", "정기결제가 3일 뒤예요", "내용", "/payment/recurring",
                "recurring:1:2026-08-28:RECURRING");

        assertEquals(InboxNotifier.Result.DUPLICATE, result);
    }

    @Test
    void should_waitUntilCommit_whenTransactionSynchronizationIsActive() {
        TransactionSynchronizationManager.initSynchronization();
        when(notificationSettingMapper.findByMemberId("member-1"))
                .thenReturn(Map.of("payment_enabled", 1));
        when(inboxNotificationWriter.write(any(), any(), any(), any(), any(), any()))
                .thenReturn("1");

        notifier.notifyAfterCommit("member-1", InboxNotifier.Channel.PAYMENT,
                "PAYMENT", "결제가 완료됐어요", "내용", "/wallet/history");
        verify(inboxNotificationWriter, never()).write(any(), any(), any(), any(), any(), any());

        TransactionSynchronizationUtils.triggerAfterCommit();
        verify(inboxNotificationWriter).write(
                eq("member-1"), eq("PAYMENT"), eq("결제가 완료됐어요"), eq("내용"),
                eq("/wallet/history"), isNull());
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
    }

    @Test
    void should_formatWonWithoutFraction() {
        assertEquals("32000원", InboxNotifier.won(new BigDecimal("32000.90")));
        assertEquals("0원", InboxNotifier.won(null));
    }
}
