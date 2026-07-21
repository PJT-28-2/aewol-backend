package com.aewol.domain.share.service;

import java.util.List;
import java.util.Map;

public interface ShareService {
    void invite(String memberId, Map<String, Object> request);
    void respondInvite(String accessId, String status);
    List<Map<String, Object>> getSharedMembers(String memberId);
}
