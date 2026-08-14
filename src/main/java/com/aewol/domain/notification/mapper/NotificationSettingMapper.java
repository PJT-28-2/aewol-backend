package com.aewol.domain.notification.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface NotificationSettingMapper {
    Map<String, Object> findByMemberId(@Param("memberId") String memberId);
    void insert(Map<String, Object> setting);
    int updatePartial(
            @Param("memberId") String memberId,
            @Param("paymentEnabled") Boolean paymentEnabled,
            @Param("recurringPaymentEnabled") Boolean recurringPaymentEnabled,
            @Param("familyShareEnabled") Boolean familyShareEnabled,
            @Param("communityEnabled") Boolean communityEnabled,
            @Param("marketingEnabled") Boolean marketingEnabled);
    void upsertForRecovery(
            @Param("memberId") Long memberId,
            @Param("marketingEnabled") boolean marketingEnabled);
}
