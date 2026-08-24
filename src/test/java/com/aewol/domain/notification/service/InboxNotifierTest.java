package com.aewol.domain.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

@ExtendWith(MockitoExtension.class)
class InboxNotifierTest {

    @Mock NotificationService notificationService;
    @Mock NotificationSettingMapper notificationSettingMapper;

    private InboxNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new InboxNotifier(notificationService, notificationSettingMapper);
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

        notifier.notifyQuietly("member-1", InboxNotifier.Channel.PAYMENT,
                "PAYMENT", "결제가 완료됐어요", "가게에서 1000원이 결제됐어요.", "/wallet/history");

        verify(notificationService).createNotification(
                "member-1", "PAYMENT", "결제가 완료됐어요",
                "가게에서 1000원이 결제됐어요.", "/wallet/history");
    }

    @Test
    void should_skipNotification_whenChannelSettingIsOff() {
        when(notificationSettingMapper.findByMemberId("member-1"))
                .thenReturn(Map.of("payment_enabled", 0, "recurring_payment_enabled", 1));

        notifier.notifyQuietly("member-1", InboxNotifier.Channel.PAYMENT,
                "PAYMENT", "결제가 완료됐어요", "내용", "/wallet/history");

        verifyNoInteractions(notificationService);
    }

    @Test
    void should_skipNotification_whenSettingsRowIsMissing() {
        when(notificationSettingMapper.findByMemberId("member-1")).thenReturn(null);

        notifier.notifyQuietly("member-1", InboxNotifier.Channel.FAMILY,
                "FAMILY_SHARE", "제목", "내용", "/share");

        verifyNoInteractions(notificationService);
    }

    @Test
    void should_notPropagate_whenCreateNotificationFails() {
        when(notificationSettingMapper.findByMemberId("member-1"))
                .thenReturn(Map.of("community_enabled", true));
        when(notificationService.createNotification(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("저장 실패"));

        notifier.notifyQuietly("member-1", InboxNotifier.Channel.COMMUNITY,
                "GROUP_PURCHASE", "제목", "내용", "/group-purchase/1");

        verify(notificationService).createNotification(any(), any(), any(), any(), any());
    }

    @Test
    void should_waitUntilCommit_whenTransactionSynchronizationIsActive() {
        TransactionSynchronizationManager.initSynchronization();
        when(notificationSettingMapper.findByMemberId("member-1"))
                .thenReturn(Map.of("payment_enabled", 1));

        notifier.notifyAfterCommit("member-1", InboxNotifier.Channel.PAYMENT,
                "PAYMENT", "결제가 완료됐어요", "내용", "/wallet/history");
        verify(notificationService, never()).createNotification(any(), any(), any(), any(), any());

        TransactionSynchronizationUtils.triggerAfterCommit();
        verify(notificationService).createNotification(
                eq("member-1"), eq("PAYMENT"), eq("결제가 완료됐어요"), eq("내용"), eq("/wallet/history"));
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
    }

    @Test
    void should_formatWonWithoutFraction() {
        assertEquals("32000원", InboxNotifier.won(new BigDecimal("32000.90")));
        assertEquals("0원", InboxNotifier.won(null));
    }
}
