package com.aewol.domain.notification.service;

import com.aewol.domain.notification.mapper.NotificationSettingMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 인앱 알림함 기록. 설정이 꺼져 있으면 만들지 않고, 저장 실패는 본 작업(결제 등)을
 * 롤백시키지 않는다. 트랜잭션이 있으면 커밋 뒤에만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InboxNotifier {

    public enum Channel {
        PAYMENT("payment_enabled", "paymentEnabled"),
        RECURRING("recurring_payment_enabled", "recurringPaymentEnabled"),
        FAMILY("family_share_enabled", "familyShareEnabled"),
        COMMUNITY("community_enabled", "communityEnabled");

        private final String snakeKey;
        private final String camelKey;

        Channel(String snakeKey, String camelKey) {
            this.snakeKey = snakeKey;
            this.camelKey = camelKey;
        }
    }

    public enum Result {
        CREATED,
        DISABLED,
        DUPLICATE,
        FAILED
    }

    private final InboxNotificationWriter inboxNotificationWriter;
    private final NotificationSettingMapper notificationSettingMapper;

    public void notifyAfterCommit(String memberId, Channel channel,
                                  String type, String title, String message, String targetPath) {
        notifyAfterCommit(memberId, channel, type, title, message, targetPath, null);
    }

    public void notifyAfterCommit(String memberId, Channel channel,
                                  String type, String title, String message, String targetPath,
                                  String eventKey) {
        if (memberId == null || memberId.isBlank()) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            notifyQuietly(memberId, channel, type, title, message, targetPath, eventKey);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                notifyQuietly(memberId, channel, type, title, message, targetPath, eventKey);
            }
        });
    }

    public Result notifyQuietly(String memberId, Channel channel,
                                String type, String title, String message, String targetPath) {
        return notifyQuietly(memberId, channel, type, title, message, targetPath, null);
    }

    public Result notifyQuietly(String memberId, Channel channel,
                                String type, String title, String message, String targetPath,
                                String eventKey) {
        if (memberId == null || memberId.isBlank()) return Result.DISABLED;
        try {
            Map<String, Object> settings = notificationSettingMapper.findByMemberId(memberId);
            if (!isEnabled(settings, channel)) return Result.DISABLED;
            inboxNotificationWriter.write(memberId, type, title, message, targetPath, eventKey);
            return Result.CREATED;
        } catch (DuplicateKeyException exception) {
            return Result.DUPLICATE;
        } catch (RuntimeException exception) {
            log.warn("[INBOX] 알림 저장 실패 - memberId: {} type: {}", memberId, type, exception);
            return Result.FAILED;
        }
    }

    public static String won(BigDecimal amount) {
        if (amount == null) return "0원";
        return amount.setScale(0, RoundingMode.DOWN).toPlainString() + "원";
    }

    public static String text(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private static boolean isEnabled(Map<String, Object> settings, Channel channel) {
        if (settings == null || channel == null) return false;
        Object value = settings.containsKey(channel.snakeKey)
                ? settings.get(channel.snakeKey)
                : settings.get(channel.camelKey);
        if (value instanceof Boolean) return (Boolean) value;
        return value instanceof Number && ((Number) value).intValue() == 1;
    }
}
