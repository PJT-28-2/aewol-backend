package com.aewol.domain.activity.service;

import com.aewol.domain.activity.mapper.ActivityLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class ActivityLogService {

    private final ActivityLogMapper activityLogMapper;

    /** V1 activity_log에는 member_id가 없다 — 행위자는 wallet_id -> wallet.member_id로 유도된다 */
    public void log(String walletId, String petId, String actionType,
                    String targetType, String targetId, String title, String description) {
        Map<String, Object> log = new HashMap<>();
        log.put("walletId", walletId);
        log.put("petId", petId);
        log.put("actionType", actionType);
        log.put("targetType", targetType);
        log.put("targetId", targetId);
        log.put("title", title);
        log.put("description", description);
        activityLogMapper.insert(log); // log_id AUTO_INCREMENT
    }

    public List<Map<String, Object>> getLogsByWallet(String walletId) {
        return activityLogMapper.findByWalletId(walletId);
    }

    public List<Map<String, Object>> getLogsByMember(String memberId) {
        return activityLogMapper.findByMemberId(memberId);
    }
}
