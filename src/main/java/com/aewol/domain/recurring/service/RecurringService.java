package com.aewol.domain.recurring.service;

import java.util.List;
import java.util.Map;

public interface RecurringService {
    List<Map<String, Object>> getRecurringPayments(String memberId);
    Map<String, Object> createRecurring(String memberId, Map<String, Object> request);
    void cancelRecurring(String recurringId);
}
