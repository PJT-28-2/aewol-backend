package com.aewol.domain.activity.service;

import com.aewol.domain.activity.mapper.ActivityLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class ActivityLogService {

    private final ActivityLogMapper activityLogMapper;

    public void log(String walletId, String memberId, String actionType,
                    String targetType, String targetId, String description) {
        Map<String, Object> log = new HashMap<>();
        log.put("logId", UUID.randomUUID().toString());
        log.put("walletId", walletId);
        log.put("memberId", memberId);
        log.put("actionType", actionType);
        log.put("targetType", targetType);
        log.put("targetId", targetId);
        log.put("description", description);
        activityLogMapper.insert(log);
    }

    public List<Map<String, Object>> getLogsByWallet(String walletId) {
        return activityLogMapper.findByWalletId(walletId);
    }

    public List<Map<String, Object>> getLogsByMember(String memberId) {
        return activityLogMapper.findByMemberId(memberId);
    }
}
