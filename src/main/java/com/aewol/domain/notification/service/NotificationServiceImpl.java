package com.aewol.domain.notification.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.notification.dto.NotificationListResponse;
import com.aewol.domain.notification.dto.NotificationResponse;
import com.aewol.domain.notification.mapper.NotificationMapper;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Pattern TYPE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,49}$");

    private final NotificationMapper notificationMapper;

    @Override
    @Transactional(readOnly = true)
    public NotificationListResponse getNotifications(String memberId, int page, int size) {
        memberId = requireMemberId(memberId);
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        long offset = (long) safePage * safeSize;
        if (offset > Integer.MAX_VALUE) {
            throw new BusinessException("페이지 범위를 확인해주세요.");
        }
        List<Map<String, Object>> rows = notificationMapper.findByMemberId(
                memberId, safeSize + 1, (int) offset);
        boolean hasNext = rows.size() > safeSize;
        List<Map<String, Object>> pageRows = hasNext ? rows.subList(0, safeSize) : rows;
        List<NotificationResponse> notifications = pageRows.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return NotificationListResponse.builder()
                .notifications(notifications)
                .unreadCount(notificationMapper.countUnread(memberId))
                .hasNext(hasNext)
                .page(safePage)
                .size(safeSize)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public int getUnreadCount(String memberId) {
        return notificationMapper.countUnread(requireMemberId(memberId));
    }

    @Override
    @Transactional
    public void markAsRead(String memberId, String notificationId) {
        memberId = requireMemberId(memberId);
        if (notificationId == null || notificationId.isBlank()
                || !notificationMapper.existsByIdAndMemberId(notificationId, memberId)) {
            // 존재 여부와 다른 사용자의 소유 여부를 구분해 노출하지 않는다.
            throw BusinessException.notFound("알림을 찾을 수 없습니다.");
        }
        // COALESCE를 사용하므로 이미 읽은 알림에 다시 호출해도 안전하다.
        notificationMapper.markAsRead(notificationId, memberId);
    }

    @Override
    @Transactional
    public void markAllAsRead(String memberId) {
        notificationMapper.markAllAsRead(requireMemberId(memberId));
    }

    @Override
    @Transactional
    public String createNotification(
            String memberId, String type, String title, String message, String targetPath) {
        memberId = requireMemberId(memberId);
        if (type == null || !TYPE_PATTERN.matcher(type).matches()) {
            throw new BusinessException("알림 종류를 확인해주세요.");
        }
        String normalizedTitle = requireText(title, 100, "알림 제목을 확인해주세요.");
        String normalizedMessage = requireText(message, 500, "알림 내용을 확인해주세요.");
        String normalizedTargetPath = normalizeTargetPath(targetPath);

        Map<String, Object> notification = new HashMap<>();
        notification.put("memberId", memberId);
        notification.put("type", type);
        notification.put("title", normalizedTitle);
        notification.put("message", normalizedMessage);
        notification.put("targetPath", normalizedTargetPath);
        notificationMapper.insert(notification);
        Object generatedId = notification.get("notificationId");
        if (generatedId == null) {
            throw new BusinessException("알림을 저장하지 못했습니다.");
        }
        return String.valueOf(generatedId);
    }

    private NotificationResponse toResponse(Map<String, Object> row) {
        Object readAt = row.get("read_at");
        return NotificationResponse.builder()
                .notificationId(text(row.get("notification_id")))
                .type(text(row.get("type")))
                .title(text(row.get("title")))
                .message(text(row.get("message")))
                .targetPath(nullableText(row.get("target_path")))
                .read(readAt != null)
                .readAt(dateTimeText(readAt))
                .createdAt(dateTimeText(row.get("created_at")))
                .build();
    }

    private String requireMemberId(String memberId) {
        if (memberId == null || memberId.isBlank()) {
            throw BusinessException.unauthorized("로그인이 필요합니다.");
        }
        return memberId;
    }

    private String requireText(String value, int maxLength, String message) {
        if (value == null || value.isBlank()) throw new BusinessException(message);
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw new BusinessException(message);
        return normalized;
    }

    private String normalizeTargetPath(String targetPath) {
        if (targetPath == null || targetPath.isBlank()) return null;
        String normalized = targetPath.trim();
        if (!normalized.startsWith("/") || normalized.startsWith("//")
                || normalized.length() > 500) {
            throw new BusinessException("알림 이동 경로를 확인해주세요.");
        }
        return normalized;
    }

    private String dateTimeText(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp) return ((Timestamp) value).toLocalDateTime().toString();
        if (value instanceof LocalDateTime) return value.toString();
        return String.valueOf(value);
    }

    private String nullableText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
