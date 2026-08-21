package com.aewol.domain.notification.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface NotificationMapper {
    int insert(Map<String, Object> notification);

    List<Map<String, Object>> findByMemberId(
            @Param("memberId") String memberId,
            @Param("limit") int limit,
            @Param("offset") int offset);

    int countUnread(@Param("memberId") String memberId);

    boolean existsByIdAndMemberId(
            @Param("notificationId") String notificationId,
            @Param("memberId") String memberId);

    int markAsRead(
            @Param("notificationId") String notificationId,
            @Param("memberId") String memberId);

    int markAllAsRead(@Param("memberId") String memberId);
}
