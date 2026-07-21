package com.aewol.domain.grouppurchase.service;

import java.util.List;
import java.util.Map;

public interface GroupPurchaseService {
    List<Map<String, Object>> list();
    Map<String, Object> create(String memberId, Map<String, Object> request);
    Map<String, Object> getDetail(String gpId);
    void join(String memberId, String gpId, int quantity);
}
