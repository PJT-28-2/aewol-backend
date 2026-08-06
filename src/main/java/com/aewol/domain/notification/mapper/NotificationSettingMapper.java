package com.aewol.domain.notification.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface NotificationSettingMapper {
    void insert(Map<String, Object> setting);
    void upsertForRecovery(
            @Param("memberId") Long memberId,
            @Param("marketingEnabled") boolean marketingEnabled);
}
